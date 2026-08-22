package com.citygo.mq.consumer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.mq.message.OrderMessage;
import com.citygo.mq.message.StockWarnMessage;
import com.citygo.order.entity.OrderItem;
import com.citygo.order.mapper.OrderItemMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 下单成功消费者（库存预警）。
 *
 * <p>演示"下单 → 异步处理"的解耦：下单主链路只负责把消息发出去，库存预警这种
 * 非核心、可延迟的处理放到消费者异步执行，不阻塞下单响应。</p>
 *
 * <p>这里模拟"库存预警通道"：当商品库存低于阈值时，把预警内容写入 Redis List
 * {@code citygo:stock.warn}（rightPush 追加），生产环境可换成对接监控/告警系统。
 * 消费者带 try-catch 兜底，单条消息处理异常不能影响同队列其他消息。</p>
 */
@Component
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);

    /** 库存预警阈值：库存低于该值即告警 */
    private static final int WARN_THRESHOLD = 10;

    /** 库存预警 Redis List key */
    private static final String STOCK_WARN_KEY = "citygo:stock.warn";

    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;

    public OrderCreatedConsumer(OrderItemMapper orderItemMapper,
                                ProductMapper productMapper,
                                StringRedisTemplate stringRedisTemplate,
                                JsonMapper jsonMapper) {
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = "citygo.order.created.queue")
    public void onOrderCreated(OrderMessage message) {
        try {
            Long orderId = message.getOrderId();
            // 查订单明细对应商品，逐个检查库存是否低于阈值
            List<OrderItem> items = orderItemMapper.selectList(
                    Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, orderId));
            for (OrderItem item : items) {
                Product product = productMapper.selectById(item.getProductId());
                if (product == null) {
                    continue;
                }
                if (product.getStock() < WARN_THRESHOLD) {
                    StockWarnMessage warn = new StockWarnMessage(
                            product.getId(), product.getProductName(), product.getStock());
                    stringRedisTemplate.opsForList().rightPush(STOCK_WARN_KEY, toJson(warn));
                    log.info("库存预警: 商品 {} ({}), 剩余库存 {}", product.getId(), product.getProductName(), product.getStock());
                }
            }
        } catch (Exception e) {
            // 兜底：不能因本条消息异常影响同队列后续消息（学习项目直接记日志）
            log.error("下单消息处理异常: orderId={}", message == null ? null : message.getOrderId(), e);
        }
    }

    private String toJson(StockWarnMessage warn) {
        try {
            return jsonMapper.writeValueAsString(warn);
        } catch (JacksonException e) {
            throw new RuntimeException("库存预警 JSON 序列化失败", e);
        }
    }

}