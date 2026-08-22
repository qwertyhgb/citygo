package com.citygo.mq.message;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付成功消息体（payment.success 广播）。
 *
 * <p>同样是跨系统契约，只放稳定字段：orderId 定位订单、paymentNo 定位支付单、
 * amount 供通知文案展示金额。消费方需要更完整信息时反查订单。</p>
 */
@Data
public class PaymentMessage {

    /** 订单ID */
    private Long orderId;

    /** 支付单号（雪花号） */
    private String paymentNo;

    /** 支付金额 */
    private BigDecimal amount;

    /** 必须有无参构造，供 Jackson 反序列化 */
    public PaymentMessage() {
    }

    public PaymentMessage(Long orderId, String paymentNo, BigDecimal amount) {
        this.orderId = orderId;
        this.paymentNo = paymentNo;
        this.amount = amount;
    }

}