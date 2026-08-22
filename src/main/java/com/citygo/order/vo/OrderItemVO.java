package com.citygo.order.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细视图对象。
 */
@Data
public class OrderItemVO {

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

}