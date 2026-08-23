package com.citygo.seckill.consumer;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.address.entity.Address;
import com.citygo.address.mapper.AddressMapper;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.mq.message.OrderMessage;
import com.citygo.order.entity.OrderItem;
import com.citygo.order.entity.Orders;
import com.citygo.order.enums.OrderSource;
import com.citygo.order.enums.OrderStatus;
import com.citygo.order.mapper.OrderItemMapper;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.service.ProductService;
import com.citygo.search.service.ProductSearchService;
import com.citygo.search.support.SearchSync;
import com.citygo.seckill.message.SeckillMessage;
import com.citygo.seckill.service.impl.SeckillServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀异步订单创建消费者。
 *
 * <p><b>秒杀高并发全链路流转图</b>：
 * <pre>
 *   [客户端抢购]
 *        ↓
 *   [接口限流 @RateLimit]
 *        ↓
 *   [Redis SETNX 一人一单去重] ──(已抢过)──→ 409 SELL_REPEAT
 *        ↓
 *   [Redis DECR 原子预扣库存] ──(库存不足)──→ INCR回滚 + 409 SELL_OUT
 *        ↓
 *   [MQ 异步削峰 (Direct: seckill.order)] ──→ 立即返回 200 "抢购成功，订单创建中"
 *        ↓ (异步消费)
 *   [SeckillOrderConsumer 消费者]
 *        ├─ a. DB 一人一单复查 (极端并发兜底) ──(已存在)──→ 丢弃消息
 *        ├─ b. 商品状态 & 时段再校验 (防下架/过期) ──(无效)──→ Redis INCR 回补 + 丢弃
 *        ├─ c. DB 秒杀库存条件更新扣减 (超卖兜底) ──(0行)──→ Redis INCR 回补 + 丢弃
 *        └─ d. 创建订单 (source=2, 待支付) + 快照 + 销量加 + 发送超时 TTL 延迟消息 + 缓存/ES 联动
 *        ↓ (若用户 30 分钟未支付)
 *   [OrderTimeoutConsumer 超时取消] ──→ 释放 seckill_stock + Redis 预扣库存回补
 * </pre>
 * </p>
 */
@Component
public class SeckillOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderConsumer.class);

    private final ProductMapper productMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShopMapper shopMapper;
    private final AddressMapper addressMapper;
    private final ProductService productService;
    private final ProductSearchService productSearchService;
    private final SearchSync searchSync;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final long timeoutMinutes;

    public SeckillOrderConsumer(ProductMapper productMapper,
                                OrdersMapper ordersMapper,
                                OrderItemMapper orderItemMapper,
                                ShopMapper shopMapper,
                                AddressMapper addressMapper,
                                ProductService productService,
                                ProductSearchService productSearchService,
                                SearchSync searchSync,
                                StringRedisTemplate stringRedisTemplate,
                                RabbitTemplate rabbitTemplate,
                                @Value("${citygo.order.timeout-minutes:30}") long timeoutMinutes) {
        this.productMapper = productMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.shopMapper = shopMapper;
        this.addressMapper = addressMapper;
        this.productService = productService;
        this.productSearchService = productSearchService;
        this.searchSync = searchSync;
        this.stringRedisTemplate = stringRedisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.timeoutMinutes = timeoutMinutes;
    }

    @RabbitListener(queues = "citygo.seckill.order.queue")
    @Transactional(rollbackFor = Exception.class)
    public void onSeckillOrder(SeckillMessage message) {
        Long userId = message.getUserId();
        Long productId = message.getProductId();

        // a. DB 一人一单复查（最终一致性兜底）：
        //    注释：Redis 防了 99% 并发，DB 复查兜住极端情况（如 Redis 宕机重启等异常）
        if (hasPurchasedInDB(userId, productId)) {
            log.warn("DB 一人一单复查未通过：用户 userId={} 已存在商品 productId={} 的有效秒杀订单，丢弃消息", userId, productId);
            return;
        }

        // b. 查商品 + 时段再校验（防超时/下架/配置变更）
        Product product = productMapper.selectById(productId);
        if (product == null || product.getStatus() == null || product.getStatus() != 1
                || product.getSeckillPrice() == null
                || product.getSeckillStock() == null
                || product.getSeckillStart() == null
                || product.getSeckillEnd() == null) {
            log.warn("秒杀商品不存在或已下架/未开启秒杀，回补 Redis 预扣库存: productId={}", productId);
            stringRedisTemplate.opsForValue().increment(SeckillServiceImpl.KEY_SECKILL_STOCK_PREFIX + productId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(product.getSeckillStart()) || now.isAfter(product.getSeckillEnd())) {
            log.warn("秒杀已结束或未开始，回补 Redis 预扣库存: productId={}", productId);
            stringRedisTemplate.opsForValue().increment(SeckillServiceImpl.KEY_SECKILL_STOCK_PREFIX + productId);
            return;
        }

        // c. DB 秒杀库存兜底：UPDATE product SET seckill_stock = seckill_stock - 1 WHERE id = ? AND seckill_stock > 0
        //    条件更新；0 行说明 DB 真实库存已售罄（超卖兜底失败）：Redis INCR 回补 + 日志 WARN + 丢弃
        int rows = productMapper.deductSeckillStock(productId, 1);
        if (rows == 0) {
            log.warn("DB 秒杀库存扣减失败（超卖兜底），回补 Redis 预扣库存并丢弃消息: productId={}", productId);
            stringRedisTemplate.opsForValue().increment(SeckillServiceImpl.KEY_SECKILL_STOCK_PREFIX + productId);
            return;
        }

        // d. 创建订单（@Transactional）
        Orders order = new Orders();
        order.setOrderNo(IdWorker.getIdStr());
        order.setUserId(userId);
        order.setShopId(product.getShopId());
        order.setTotalAmount(product.getSeckillPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setPayAmount(product.getSeckillPrice());
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setSource(OrderSource.SECKILL.getCode());

        // 收货信息快照：查用户默认地址，无默认取第一条；再无则快照置空并记 WARN（秒杀场景支持先抢后补地址）
        List<Address> addresses = addressMapper.selectList(
                Wrappers.<Address>lambdaQuery()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getIsDefault)
                        .orderByDesc(Address::getId));
        if (!addresses.isEmpty()) {
            Address addr = addresses.get(0);
            order.setReceiverName(addr.getReceiverName());
            order.setReceiverPhone(addr.getReceiverPhone());
            order.setReceiverAddress(addr.getProvince() + addr.getCity() + addr.getDistrict() + addr.getDetailAddress());
        } else {
            log.warn("秒杀下单用户 userId={} 未配置收货地址，快照置空（秒杀支持先抢后补地址）", userId);
            order.setReceiverName("");
            order.setReceiverPhone("");
            order.setReceiverAddress("");
        }
        ordersMapper.insert(order);

        // 插入订单明细快照
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setProductId(product.getId());
        orderItem.setProductName(product.getProductName());
        orderItem.setProductImage(product.getCoverImage());
        orderItem.setPrice(product.getSeckillPrice());
        orderItem.setQuantity(1);
        orderItem.setTotalAmount(product.getSeckillPrice());
        orderItemMapper.insert(orderItem);

        // 销量累加
        productMapper.addSales(productId, 1);
        shopMapper.addMonthlySales(product.getShopId(), 1);

        // 发送超时 TTL 延迟消息（复用普通订单下单超时机制：TTL 30 分钟）
        OrderMessage delayMsg = new OrderMessage(order.getId(), order.getOrderNo());
        long ttlMillis = timeoutMinutes * 60 * 1000;
        rabbitTemplate.convertAndSend("citygo.order.delay.exchange", "order.delay", delayMsg,
                m -> {
                    m.getMessageProperties().setExpiration(String.valueOf(ttlMillis));
                    return m;
                });

        // 缓存失效与 ES 近实时同步
        productService.evictProductDetail(productId);
        productService.evictHotProducts();
        searchSync.afterCommit(() -> productSearchService.syncProducts(
                productMapper.selectByIds(List.of(productId))));

        log.info("秒杀订单创建成功: orderId={}, orderNo={}, userId={}, productId={}",
                order.getId(), order.getOrderNo(), userId, productId);
    }

    /**
     * DB 一人一单复查：查该用户是否已购买过该商品的秒杀订单（非已取消状态）。
     */
    private boolean hasPurchasedInDB(Long userId, Long productId) {
        List<Orders> seckillOrders = ordersMapper.selectList(
                Wrappers.<Orders>lambdaQuery()
                        .eq(Orders::getUserId, userId)
                        .eq(Orders::getSource, OrderSource.SECKILL.getCode())
                        .ne(Orders::getStatus, OrderStatus.CANCELLED.getCode()));
        if (seckillOrders.isEmpty()) {
            return false;
        }
        List<Long> orderIds = seckillOrders.stream().map(Orders::getId).toList();
        Long count = orderItemMapper.selectCount(
                Wrappers.<OrderItem>lambdaQuery()
                        .in(OrderItem::getOrderId, orderIds)
                        .eq(OrderItem::getProductId, productId));
        return count != null && count > 0;
    }

}
