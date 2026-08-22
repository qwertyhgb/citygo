package com.citygo.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改商品库存请求体。
 */
@Data
public class ProductStockRequest {

    /** 库存 */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

}