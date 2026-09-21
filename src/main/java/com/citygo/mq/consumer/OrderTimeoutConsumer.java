package com.citygo.mq.consumer;

import com.citygo.common.exception.BizException;
import com.citygo.mq.config.RabbitConfig;
import com.citygo.mq.message.OrderMessage;
import com.citygo.order.entity.Orders;
import com.citygo.order.enums.OrderStatus;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时消费者（系统自动取消待支付订单）。
 *
 * <p>配合 TTL+死信队列：下单时发送带 TTL 的延迟消息，到期无人支付 → 转投死信交换机 →
 * 进入超时队列 → 由本消费者执行系统取消。</p>
 *
 * <p><b>幂等保证</b>：消费者先查订单，不存在或状态 != 10（待支付）直接忽略；
 * 即便并发下用户恰好同时支付/取消，{@code cancelBySystem} 内部的状态条件更新（WHERE status=10）
 * 0 行即视为该订单已被其他路径处理，抛出 BizException 被这里捕获转 WARN 日志，不会重复回补库存。</p>
 */
@Component
public class OrderTimeoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    private final OrdersMapper ordersMapper;
    private final OrderService orderService;

    public OrderTimeoutConsumer(OrdersMapper ordersMapper, OrderService orderService) {
        this.ordersMapper = ordersMapper;
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ORDER_TIMEOUT)
    public void onTimeout(OrderMessage message) {
        Long orderId = message.getOrderId();
        // 幂等前置：订单不存在或已非待支付，直接忽略（可能已支付/已取消）
        Orders order = ordersMapper.selectById(orderId);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT.getCode()) {
            log.info("超时消息忽略: 订单 orderId={} 不存在或非待支付", orderId);
            return;
        }
        try {
            orderService.cancelBySystem(orderId);
            log.info("订单超时自动取消: orderId={}", orderId);
        } catch (BizException e) {
            // 并发下用户恰好同时取消/支付时，条件更新 0 行即视为已处理
            log.warn("订单超时取消未执行(可能已被处理): orderId={}, reason={}", orderId, e.getMessage());
        }
    }

}