package com.citygo.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ 交换机 / 队列 / 绑定 / 消息转换器配置。
 *
 * <p><b>超时方案：为什么用 TTL + 死信队列（DLX），而不用 RabbitMQ 延迟消息插件</b>：</p>
 * <ul>
 *   <li>knowflow-rabbitmq 是<b>官方镜像</b>，不带延迟消息插件（rabbitmq-delayed-message-exchange）；
 *       且红线禁令<b>禁止修改容器/安装插件</b>，因此只能使用 RabbitMQ 原生能力；</li>
 *   <li>TTL + DLX 是标准协议能力（x-message-ttl / x-dead-letter-exchange），零插件即可实现"延迟消费"；
 *       面试也能讲清楚"消息到期 → 投递死信交换机 → 死信消费者处理"这条链路。</li>
 * </ul>
 *
 * <p><b>超时链路完整流程</b>：
 * 下单 → 发送 TTL 消息到延迟交换机 → 延迟队列（到期无人消费）→ 消息进死信交换机 →
 * 死信交换机按 routing-key=timeout → 超时队列 → OrderTimeoutConsumer → 状态机校验（10→60）→ 取消并回补库存。</p>
 *
 * <p><b>TTL+DLX 优缺点（面试点）</b>：优点——纯原生、无需插件；缺点——队列级 TTL 有"队头阻塞"
 * （队首短消息不先过期时，后面即使过期也得等），本项目用<b>消息级 TTL</b>，每条消息独立计时，规避队头阻塞。</p>
 */
@Configuration
public class RabbitConfig {

    // ==================== 消息转换器 ====================

    /**
     * 消息转换器：JSON 序列化（存入 RabbitMQ 的是可读 JSON）。
     *
     * <p>注意：SB4 对应 Spring AMQP 已弃用 Jackson2JsonMessageConverter（Jackson 2 系列），
     * 必须用 Jackson 3 版 {@link JacksonJsonMessageConverter}（同包名，类名不带 2）。
     * 构造参数用 Spring Boot 自动配置的 {@link JsonMapper}（tools.jackson）。</p>
     *
     * <p>声明该 @Bean 后，Spring Boot 自动配置会把此转换器应用到 RabbitTemplate 与
     * 监听器容器；@RabbitListener 方法参数直接写具体消息类型（如 OrderMessage）
     * 即可自动反序列化。</p>
     */
    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }

    // ==================== 一、下单异步（Direct：order.created） ====================

    /** 下单成功交换机（Direct）。routing key = order.created，投递给库存预警队列。 */
    @Bean
    public DirectExchange orderCreatedExchange() {
        return new DirectExchange("citygo.order.created.exchange", true, false);
    }

    /** 下单成功队列：库存预警消费者监听。 */
    @Bean
    public Queue orderCreatedQueue() {
        return new Queue("citygo.order.created.queue", true);
    }

    /** 绑定：交换机 → 队列，routing key = order.created。 */
    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder.bind(orderCreatedQueue()).to(orderCreatedExchange()).with("order.created");
    }

    // ==================== 二、支付成功广播（Fanout：payment.success） ====================

    /**
     * 支付成功广播交换机（Fanout）。
     *
     * <p>Fanout 不区分 routing key，绑定的所有队列都会收到消息 → 天然实现"一次支付、多渠道通知"。
     * 后续新增通知渠道（短信/推送）只需再加一条队列绑定，<b>无需改动支付代码</b>，这是广播解耦的核心价值。</p>
     */
    @Bean
    public FanoutExchange paymentSuccessExchange() {
        return new FanoutExchange("citygo.payment.success.exchange", true, false);
    }

    /** 支付通知-用户队列：通知用户消费者监听。 */
    @Bean
    public Queue paymentNotifyUserQueue() {
        return new Queue("citygo.payment.notify.user.queue", true);
    }

    /** 支付通知-商家队列：通知商家消费者监听。 */
    @Bean
    public Queue paymentNotifyMerchantQueue() {
        return new Queue("citygo.payment.notify.merchant.queue", true);
    }

    /** 用户队列绑定（Fanout：无 routing key）。 */
    @Bean
    public Binding paymentNotifyUserBinding() {
        return BindingBuilder.bind(paymentNotifyUserQueue()).to(paymentSuccessExchange());
    }

    /** 商家队列绑定（Fanout：无 routing key）。 */
    @Bean
    public Binding paymentNotifyMerchantBinding() {
        return BindingBuilder.bind(paymentNotifyMerchantQueue()).to(paymentSuccessExchange());
    }

    // ==================== 三、订单超时（TTL 延迟队列 + 死信队列 DLX） ====================

    /** 延迟交换机（Direct）。下单后按 routing key = order.delay 发消息到这里，进入延迟队列。 */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange("citygo.order.delay.exchange", true, false);
    }

    /**
     * 延迟队列：消息带【消息级 TTL】，到期无人消费即转投死信交换机。
     *
     * <p>队列属性：
     * <ul>
     *   <li>不设队列级 x-message-ttl（用消息级 TTL，便于测试用小值、避免队头阻塞）；</li>
     *   <li>x-dead-letter-exchange = citygo.order.dlx.exchange（消息过期转投此处）；</li>
     *   <li>x-dead-letter-routing-key = timeout（死信交换机据此路由到超时队列）。</li>
     * </ul>
     * 消息到期无人消费 → 进死信交换机 → 转到超时队列。</p>
     */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable("citygo.order.delay.queue")
                .deadLetterExchange("citygo.order.dlx.exchange")
                .deadLetterRoutingKey("timeout")
                .build();
    }

    /** 绑定：延迟交换机 → 延迟队列，routing key = order.delay。 */
    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with("order.delay");
    }

    /** 死信交换机（Direct）。接收延迟队列到期转投的消息。 */
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange("citygo.order.dlx.exchange", true, false);
    }

    /** 超时队列：系统取消订单消费者监听。 */
    @Bean
    public Queue orderTimeoutQueue() {
        return new Queue("citygo.order.timeout.queue", true);
    }

    /** 绑定：死信交换机 → 超时队列，routing key = timeout。 */
    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(orderDlxExchange()).with("timeout");
    }

    // ==================== 四、秒杀异步下单（Direct：seckill.order） ====================

    /** 秒杀订单交换机（Direct）。routing key = seckill.order，投递给秒杀异步下单队列。 */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange("citygo.seckill.order.exchange", true, false);
    }

    /** 秒杀异步下单队列：秒杀消费者监听。 */
    @Bean
    public Queue seckillOrderQueue() {
        return new Queue("citygo.seckill.order.queue", true);
    }

    /** 绑定：秒杀交换机 → 秒杀队列，routing key = seckill.order。 */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue()).to(seckillOrderExchange()).with("seckill.order");
    }

}