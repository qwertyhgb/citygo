package com.citygo.mq.consumer;

import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.mq.config.RabbitConfig;
import com.citygo.mq.message.PaymentMessage;
import com.citygo.order.entity.Orders;
import com.citygo.order.mapper.OrdersMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 支付成功广播消费者（通知用户 + 通知商家，Fanout 的一个类两个监听方法）。
 *
 * <p><b>fanout 广播的解耦价值</b>：一条支付成功消息同时进「用户通知」「商家通知」两个队列，
 * 两个消费者互不依赖；未来新增通知渠道（短信/App 推送）只需加一条队列绑定，<b>支付代码零改动</b>。</p>
 *
 * <p><b>幂等说明</b>：通知类消费者天然幂等——重复收到同一条支付消息最多是多发一次通知，
 * 副作用可接受；而"业务类"消费者（如订单超时取消）必须用条件更新保证幂等，见 OrderTimeoutConsumer。</p>
 *
 * <p>通知内容写入 Redis List：{@code citygo:notify:{userId}} / {@code citygo:notify:merchant:{merchantId}}，
 * 供前端轮询拉取，模拟"站内信/通知中心"。</p>
 */
@Component
public class PaymentNotifyConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifyConsumer.class);

    /** 用户通知 Redis key 前缀：citygo:notify:{userId} */
    private static final String USER_NOTIFY_PREFIX = "citygo:notify:";

    /** 商家通知 Redis key 前缀：citygo:notify:merchant:{merchantId} */
    private static final String MERCHANT_NOTIFY_PREFIX = "citygo:notify:merchant:";

    private final OrdersMapper ordersMapper;
    private final ShopMapper shopMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public PaymentNotifyConsumer(OrdersMapper ordersMapper,
                                 ShopMapper shopMapper,
                                 StringRedisTemplate stringRedisTemplate) {
        this.ordersMapper = ordersMapper;
        this.shopMapper = shopMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 通知用户 */
    @RabbitListener(queues = RabbitConfig.QUEUE_PAYMENT_NOTIFY_USER)
    public void notifyUser(PaymentMessage message) {
        try {
            Orders order = ordersMapper.selectById(message.getOrderId());
            if (order == null) {
                log.warn("支付通知用户: 订单不存在 orderId={}", message.getOrderId());
                return;
            }
            String content = "您的订单 " + order.getOrderNo()
                    + " 已支付成功，金额 " + message.getAmount() + " 元";
            stringRedisTemplate.opsForList().rightPush(USER_NOTIFY_PREFIX + order.getUserId(), content);
            log.info("通知用户 {}: {}", order.getUserId(), content);
        } catch (Exception e) {
            log.error("支付通知用户处理异常: {}", message.getOrderId(), e);
        }
    }

    /** 通知商家 */
    @RabbitListener(queues = RabbitConfig.QUEUE_PAYMENT_NOTIFY_MERCHANT)
    public void notifyMerchant(PaymentMessage message) {
        try {
            Orders order = ordersMapper.selectById(message.getOrderId());
            if (order == null) {
                log.warn("支付通知商家: 订单不存在 orderId={}", message.getOrderId());
                return;
            }
            // 通过订单店铺获取商家ID：订单 -> shopId -> shop.merchantId
            Shop shop = shopMapper.selectById(order.getShopId());
            if (shop == null) {
                log.warn("支付通知商家: 店铺不存在 shopId={}", order.getShopId());
                return;
            }
            String content = "用户订单 " + order.getOrderNo()
                    + " 已支付成功，金额 " + message.getAmount() + " 元";
            stringRedisTemplate.opsForList().rightPush(MERCHANT_NOTIFY_PREFIX + shop.getMerchantId(), content);
            log.info("通知商家 {}: {}", shop.getMerchantId(), content);
        } catch (Exception e) {
            log.error("支付通知商家处理异常: {}", message.getOrderId(), e);
        }
    }

}