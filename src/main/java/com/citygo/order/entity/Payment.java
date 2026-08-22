package com.citygo.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体，对应数据库 {@code payment} 表。
 *
 * <p>一单多次支付尝试只允许一条成功记录，靠唯一索引 uk_payment_no 兜底防重复支付。</p>
 */
@Data
@TableName("payment")
public class Payment {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 支付流水号（全局唯一） */
    private String paymentNo;

    /** 订单ID */
    private Long orderId;

    /** 支付用户ID */
    private Long userId;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付方式：1 微信 2 支付宝 3 模拟支付 */
    private Integer payMethod;

    /** 状态：1 待支付 2 支付成功 3 支付失败 4 已退款 */
    private Integer status;

    /** 支付成功时间 */
    private LocalDateTime paidTime;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}