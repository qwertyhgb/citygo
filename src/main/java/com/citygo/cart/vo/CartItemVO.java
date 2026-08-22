package com.citygo.cart.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 购物车条目视图对象。
 *
 * <p>{@code offSale} 用于标记商品已删除或已下架，前端可据此提示失效商品。</p>
 */
@Data
public class CartItemVO {

    /** 商品ID */
    private Long productId;

    /** 商品名称 */
    private String productName;

    /** 商品主图 */
    private String coverImage;

    /** 商品实时单价 */
    private BigDecimal price;

    /** 购买数量 */
    private Integer quantity;

    /** 小计（price × quantity） */
    private BigDecimal subtotal;

    /** 是否失效：当前商品已删除或已下架时为 true */
    private Boolean offSale;

}