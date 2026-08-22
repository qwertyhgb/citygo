package com.citygo.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改商品上下架状态请求体。
 */
@Data
public class ProductStatusRequest {

    /** 状态：1 上架 0 下架 */
    @NotNull(message = "商品状态不能为空")
    private Integer status;

}