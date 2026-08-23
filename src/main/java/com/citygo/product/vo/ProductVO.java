package com.citygo.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品视图对象。
 *
 * <p>在商品基础字段上补充分类名称（categoryName），便于前端直接展示。</p>
 */
@Data
public class ProductVO {

    /** 商品ID */
    private Long id;

    /** 所属店铺ID */
    private Long shopId;

    /** 分类ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 商品名称 */
    private String productName;

    /** 商品描述 */
    private String description;

    /** 主图 URL */
    private String coverImage;

    /** 轮播图（逗号分隔） */
    private String images;

    /** 售价 */
    private BigDecimal price;

    /** 原价（划线价） */
    private BigDecimal originalPrice;

    /** 秒杀价（NULL 表示不参与秒杀） */
    private BigDecimal seckillPrice;

    /** 秒杀库存（NULL 表示不参与秒杀） */
    private Integer seckillStock;

    /** 秒杀开始时间 */
    private LocalDateTime seckillStart;

    /** 秒杀结束时间 */
    private LocalDateTime seckillEnd;

    /** 库存 */
    private Integer stock;

    /** 销量 */
    private Integer sales;

    /** 状态：1 上架 0 下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

}