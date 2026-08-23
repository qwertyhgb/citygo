package com.citygo.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，对应数据库 {@code orders} 表。
 */
@Data
@TableName("orders")
public class Orders {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务订单号（雪花号，全局唯一） */
    private String orderNo;

    /** 下单用户ID */
    private Long userId;

    /** 店铺ID */
    private Long shopId;

    /** 商品总额 */
    private BigDecimal totalAmount;

    /** 优惠金额 */
    private BigDecimal discountAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 订单状态：10 待支付 20 已支付 30 商家已接单 40 配送中 50 已完成 60 已取消 70 退款中 80 已退款 */
    private Integer status;

    /** 订单来源：1 普通 2 秒杀 */
    private Integer source;

    /** 收货人姓名（下单时快照） */
    private String receiverName;

    /** 收货人电话（快照） */
    private String receiverPhone;

    /** 收货地址（快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 完成时间 */
    private LocalDateTime completedTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 取消原因 */
    private String cancelReason;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}