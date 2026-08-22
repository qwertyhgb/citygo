package com.citygo.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新购物车条目请求体。
 */
@Data
public class CartItemUpdateRequest {

    /** 数量（>= 0；0 表示删除该条目） */
    @NotNull(message = "数量不能为空")
    @Min(value = 0, message = "数量不能为负数")
    private Integer quantity;

}