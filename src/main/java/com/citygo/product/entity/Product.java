package com.citygo.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体，对应数据库 {@code product} 表。
 *
 * <p>商品挂靠在店铺（{@code shopId}）下并归属某分类（{@code categoryId}）；
 * {@code sales} 为冗余销量字段（下单时累加）。</p>
 */
@Data
@TableName("product")
public class Product {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属店铺ID */
    private Long shopId;

    /** 分类ID */
    private Long categoryId;

    /** 商品名称 */
    private String productName;

    /** 商品描述 */
    private String description;

    /** 主图 URL */
    private String coverImage;

    /** 轮播图（逗号分隔多个 URL） */
    private String images;

    /** 售价 */
    private BigDecimal price;

    /** 原价（划线价） */
    private BigDecimal originalPrice;

    /** 库存 */
    private Integer stock;

    /** 销量（冗余字段） */
    private Integer sales;

    /** 状态：1 上架 0 下架 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}