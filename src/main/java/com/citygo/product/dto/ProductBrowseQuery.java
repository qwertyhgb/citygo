package com.citygo.product.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

/**
 * 商品浏览列表查询参数对象。
 *
 * <p>GET 请求的查询参数由 Spring 自动绑定到本对象（@ModelAttribute 默认行为），
 * 全部字段可空。</p>
 */
@Data
public class ProductBrowseQuery {

    /** 店铺ID（可选） */
    private Long shopId;

    /** 分类ID（可选） */
    private Long categoryId;

    /** 关键词（可选，商品名模糊匹配） */
    private String keyword;

    /** 最低价（可选，>= 0） */
    @DecimalMin(value = "0", message = "最低价不能为负数")
    private java.math.BigDecimal minPrice;

    /** 最高价（可选，>= 0） */
    @DecimalMin(value = "0", message = "最高价不能为负数")
    private java.math.BigDecimal maxPrice;

    /** 排序方式：default | sales | price_asc | price_desc；默认 default */
    private String sort;

    /** 页码（从 1 开始） */
    private long pageNum = 1;

    /** 每页条数（1-50） */
    private long pageSize = 10;

}