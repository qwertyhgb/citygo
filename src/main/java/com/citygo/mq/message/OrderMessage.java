package com.citygo.mq.message;

import lombok.Data;

/**
 * 下单成功消息体（order.created / 超时延迟消息共用）。
 *
 * <p><b>消息体是跨系统契约</b>：一旦发布到 MQ，字段增删会影响所有订阅方。
 * 因此这里只放稳定、核心的字段（orderId 用于定位订单，orderNo 用于展示/日志），
 * 不做业务字段的堆积——需要更多数据时由消费者反查订单获得，保证契约轻量稳定。</p>
 */
@Data
public class OrderMessage {

    /** 订单ID（雪花号，定位唯一订单） */
    private Long orderId;

    /** 业务订单号（对外展示，便于日志与排查） */
    private String orderNo;

    /** 必须有无参构造，供 Jackson 反序列化 */
    public OrderMessage() {
    }

    public OrderMessage(Long orderId, String orderNo) {
        this.orderId = orderId;
        this.orderNo = orderNo;
    }

}