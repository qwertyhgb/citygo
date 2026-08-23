package com.citygo.seckill.service.impl;

import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import com.citygo.search.service.ProductSearchService;
import com.citygo.search.support.SearchSync;
import com.citygo.seckill.dto.SeckillConfigRequest;
import com.citygo.seckill.message.SeckillMessage;
import com.citygo.seckill.service.SeckillService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀业务服务实现。
 *
 * <p>核心高并发设计：
 * <ul>
 *   <li><b>一人一单</b>：Redis SETNX 原子去重，TTL 覆盖活动期，规避 DB 查重导致的并发穿透与性能瓶颈；</li>
 *   <li><b>库存预扣</b>：Redis DECR 原子操作在内存完成库存扣减，防止超卖与行锁竞争；</li>
 *   <li><b>异步削峰</b>：预扣成功后投递 Direct 交换机至 MQ 队列，削峰异步落库创建订单；</li>
 *   <li><b>多重兜底</b>：消费者端再次进行 DB 一人一单复查与 DB 条件更新库存扣减，极端场景回补 Redis。</li>
 * </ul>
 * </p>
 */
@Service
public class SeckillServiceImpl implements SeckillService {

    /** Redis 秒杀库存 key 前缀：citygo:seckill:stock:{productId} */
    public static final String KEY_SECKILL_STOCK_PREFIX = "citygo:seckill:stock:";

    /** Redis 秒杀用户去重 key 前缀：citygo:seckill:user:{productId}:{userId} */
    public static final String KEY_SECKILL_USER_PREFIX = "citygo:seckill:user:";

    private final ProductMapper productMapper;
    private final ProductService productService;
    private final MerchantService merchantService;
    private final ShopService shopService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ProductSearchService productSearchService;
    private final SearchSync searchSync;

    public SeckillServiceImpl(ProductMapper productMapper,
                              ProductService productService,
                              MerchantService merchantService,
                              ShopService shopService,
                              StringRedisTemplate stringRedisTemplate,
                              RabbitTemplate rabbitTemplate,
                              ProductSearchService productSearchService,
                              SearchSync searchSync) {
        this.productMapper = productMapper;
        this.productService = productService;
        this.merchantService = merchantService;
        this.shopService = shopService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.productSearchService = productSearchService;
        this.searchSync = searchSync;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductVO configSeckill(Long productId, SeckillConfigRequest request, Long currentUserId) {
        // 1. 时间合法性校验：结束时间必须晚于开始时间
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BizException(ErrorCode.BAD_REQUEST);
        }

        // 2. 商家身份与商品存在性校验
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        // 3. 店铺归属校验：必须是当前商家所属店铺下的商品
        Long owner = shopService.getMerchantIdOfShop(product.getShopId());
        if (owner == null || !owner.equals(merchant.getId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }

        // 4. 更新数据库商品秒杀配置字段
        product.setSeckillPrice(request.getSeckillPrice());
        product.setSeckillStock(request.getSeckillStock());
        product.setSeckillStart(request.getStartTime());
        product.setSeckillEnd(request.getEndTime());
        productMapper.updateById(product);

        // 5. 初始化/覆盖 Redis 预扣库存：
        //    说明：配置变更后以最新配置为准，直接 set 覆盖，使秒杀库存即时生效
        String stockKey = KEY_SECKILL_STOCK_PREFIX + productId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(request.getSeckillStock()));

        // 6. 失效商品详情/热门缓存，并在事务提交后同步 ES 商品文档
        productService.evictProductDetail(productId);
        productService.evictHotProducts();
        searchSync.afterCommit(() -> productSearchService.syncProducts(
                List.of(productMapper.selectById(productId))));

        // 7. 返回最新商品视图
        return toVO(product);
    }

    private ProductVO toVO(Product product) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setShopId(product.getShopId());
        vo.setCategoryId(product.getCategoryId());
        vo.setProductName(product.getProductName());
        vo.setDescription(product.getDescription());
        vo.setCoverImage(product.getCoverImage());
        vo.setImages(product.getImages());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setSeckillPrice(product.getSeckillPrice());
        vo.setSeckillStock(product.getSeckillStock());
        vo.setSeckillStart(product.getSeckillStart());
        vo.setSeckillEnd(product.getSeckillEnd());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }

    @Override
    public void seckill(Long productId, Long userId) {
        // a. 查商品：商品必须存在且完整配置了秒杀信息，否则返回 SELL_NOT_AVAILABLE
        //    为什么：防止对普通未开启秒杀的商品进行秒杀请求；未配置秒杀的商品不能抢购
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1
                || product.getSeckillPrice() == null
                || product.getSeckillStock() == null
                || product.getSeckillStart() == null
                || product.getSeckillEnd() == null) {
            throw new BizException(ErrorCode.SELL_NOT_AVAILABLE);
        }

        // b. 时段校验：
        //    为什么：不在活动时间窗口内的请求必须被立刻拦截，避免非活动时间扣减库存
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(product.getSeckillStart())) {
            throw new BizException(ErrorCode.SELL_NOT_STARTED);
        }
        if (now.isAfter(product.getSeckillEnd())) {
            throw new BizException(ErrorCode.SELL_ENDED);
        }

        // c. 一人一单（Redis SETNX 原子去重）：
        //    为什么：Redis 原子去重扛住高并发，比查数据库快且不压 DB；TTL 设置为到秒杀活动结束的剩余时间
        String userKey = KEY_SECKILL_USER_PREFIX + productId + ":" + userId;
        Duration ttl = Duration.between(now, product.getSeckillEnd());
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofSeconds(1);
        }
        Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(userKey, "1", ttl);
        if (absent == null || !absent) {
            throw new BizException(ErrorCode.SELL_REPEAT);
        }

        // d. 预扣库存（Redis DECR 原子操作）：
        //    为什么：DECR 原子操作解决高并发库存竞争，瞬间在内存完成超卖控制；
        //    预扣成功后即使后面落库失败，也由订单超时/消费者异常补偿机制回退
        String stockKey = KEY_SECKILL_STOCK_PREFIX + productId;
        Long remain = stringRedisTemplate.opsForValue().decrement(stockKey);
        if (remain == null || remain < 0) {
            // 库存不足，DECR 扣成了负数，必须原子 INCR 回滚，并释放当前用户的抢购锁标记
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.delete(userKey);
            throw new BizException(ErrorCode.SELL_OUT);
        }

        // e. 发送 MQ 异步下单消息：
        //    说明：直接发送到 Direct 交换机；若发送失败补偿策略见消费者注释（本学习项目不做本地消息表）
        rabbitTemplate.convertAndSend("citygo.seckill.order.exchange", "seckill.order",
                new SeckillMessage(userId, productId));

        // f. 抢购成功（控制器返回 Result.success("抢购成功，订单创建中")）
    }

}
