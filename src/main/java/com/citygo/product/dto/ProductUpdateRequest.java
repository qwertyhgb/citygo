package com.citygo.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新商品请求体。
 *
 * <p>不含 shopId / stock / status（这些通过独立接口修改或不允许修改）。</p>
 */
@Data
public class ProductUpdateRequest {

    /** 分类ID */
    private Long categoryId;

    /** 商品名称 */
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称长度不能超过 100")
    private String productName;

    /** 商品描述 */
    @Size(max = 1000, message = "商品描述长度不能超过 1000")
    private String description;

    /** 主图 URL */
    private String coverImage;

    /** 轮播图（逗号分隔） */
    private String images;

    /** 售价 */
    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价必须大于 0")
    private BigDecimal price;

    /** 原价 */
    @DecimalMin(value = "0.01", message = "原价必须大于 0")
    private BigDecimal originalPrice;

}