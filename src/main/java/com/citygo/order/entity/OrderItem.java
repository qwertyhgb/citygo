package com.citygo.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细实体，对应数据库 {@code order_item} 表。
 *
 * <p>商品名称/图片/单价均为下单时刻的快照，防止商品改价/删除后订单数据失真。</p>
 */
@Data
@TableName("order_item")
public class OrderItem {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 商品ID */
    private Long productId;

    /** 商品名称（快照） */
    private String productName;

    /** 商品主图（快照） */
    private String productImage;

    /** 成交单价（快照） */
    private BigDecimal price;

    /** 购买数量 */
    private Integer quantity;

    /** 小计金额 */
    private BigDecimal totalAmount;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}