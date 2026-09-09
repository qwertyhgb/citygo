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

    /** Redis 秒杀商品配置缓存 key 前缀：citygo:seckill:info:{productId} */
    public static final String KEY_SECKILL_INFO_PREFIX = "citygo:seckill:info:";

    /**
     * 秒杀原子 Lua 脚本：一人一单查重 + 库存扣减 + 用户占位。
     *
     * <p>返回值说明：
     * <ul>
     *   <li>1: 抢购成功（库存扣减且用户占位成功）；</li>
     *   <li>-1: 重复抢购（用户已抢购过该商品）；</li>
     *   <li>-2: 秒杀售罄（库存不足）。</li>
     * </ul>
     * </p>
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SECKILL_LUA_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('exists', KEYS[2]) == 1 then\n" +
                    "    return -1\n" +
                    "end\n" +
                    "local stock = tonumber(redis.call('get', KEYS[1]) or '-1')\n" +
                    "if stock <= 0 then\n" +
                    "    return -2\n" +
                    "end\n" +
                    "redis.call('decr', KEYS[1])\n" +
                    "redis.call('set', KEYS[2], '1', 'EX', ARGV[1])\n" +
                    "return 1",
                    Long.class);

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

        // 5. 初始化/覆盖 Redis 预扣库存与秒杀配置缓存（热点数据预热，秒杀请求零查库）
        String stockKey = KEY_SECKILL_STOCK_PREFIX + productId;
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(request.getSeckillStock()));

        cacheSeckillInfo(product);

        // 6. 失效商品详情/热门缓存，并在事务提交后同步 ES 商品文档
        productService.evictProductDetail(productId);
        productService.evictHotProducts();
        searchSync.afterCommit(() -> productSearchService.syncProducts(
                List.of(productMapper.selectById(productId))));

        // 7. 返回最新商品视图
        return toVO(product);
    }

    private void cacheSeckillInfo(Product product) {
        if (product == null || product.getSeckillStart() == null || product.getSeckillEnd() == null) {
            return;
        }
        String infoKey = KEY_SECKILL_INFO_PREFIX + product.getId();
        String infoValue = product.getStatus() + "|" + product.getSeckillStart() + "|"
                + product.getSeckillEnd() + "|" + product.getSeckillPrice();
        Duration ttl = Duration.between(LocalDateTime.now(), product.getSeckillEnd()).plusHours(1);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofHours(24);
        }
        stringRedisTemplate.opsForValue().set(infoKey, infoValue, ttl);
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
        // a. 获取秒杀商品时段与状态配置（优先读 Redis 缓存，防止瞬时峰值穿透打垮 MySQL）
        String infoKey = KEY_SECKILL_INFO_PREFIX + productId;
        String infoValue = stringRedisTemplate.opsForValue().get(infoKey);

        int status;
        LocalDateTime startTime;
        LocalDateTime endTime;

        if (infoValue != null) {
            String[] parts = infoValue.split("\\|");
            status = Integer.parseInt(parts[0]);
            startTime = LocalDateTime.parse(parts[1]);
            endTime = LocalDateTime.parse(parts[2]);
        } else {
            // 缓存未命中（降级回源 MySQL 并回填缓存）
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
            status = product.getStatus();
            startTime = product.getSeckillStart();
            endTime = product.getSeckillEnd();
            cacheSeckillInfo(product);
        }

        if (status != 1) {
            throw new BizException(ErrorCode.SELL_NOT_AVAILABLE);
        }

        // b. 时段校验
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startTime)) {
            throw new BizException(ErrorCode.SELL_NOT_STARTED);
        }
        if (now.isAfter(endTime)) {
            throw new BizException(ErrorCode.SELL_ENDED);
        }

        // c. Redis Lua 脚本原子执行【一人一单查重 + 库存扣减 + 用户占位】（零并发间隙与多次网络往返）
        String userKey = KEY_SECKILL_USER_PREFIX + productId + ":" + userId;
        String stockKey = KEY_SECKILL_STOCK_PREFIX + productId;
        Duration ttl = Duration.between(now, endTime);
        long ttlSeconds = ttl.isNegative() || ttl.isZero() ? 1L : ttl.getSeconds();

        Long result = stringRedisTemplate.execute(
                SECKILL_LUA_SCRIPT,
                List.of(stockKey, userKey),
                String.valueOf(ttlSeconds));

        if (result == null || result == -2L) {
            throw new BizException(ErrorCode.SELL_OUT);
        } else if (result == -1L) {
            throw new BizException(ErrorCode.SELL_REPEAT);
        }

        // d. 发送 MQ 异步下单消息
        rabbitTemplate.convertAndSend("citygo.seckill.order.exchange", "seckill.order",
                new SeckillMessage(userId, productId));
    }

}
