package com.citygo.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建商品请求体。
 */
@Data
public class ProductCreateRequest {

    /** 所属店铺ID */
    @NotNull(message = "店铺ID不能为空")
    private Long shopId;

    /** 分类ID */
    @NotNull(message = "分类ID不能为空")
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

    /** 原价（可空） */
    @DecimalMin(value = "0.01", message = "原价必须大于 0")
    private BigDecimal originalPrice;

    /** 库存 */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

}