package com.citygo.search.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * 商品搜索文档（索引 {@code citygo_product}）。
 *
 * <p>冗余 shopName/categoryName 便于搜索展示与过滤；productName + description 两个 IK 字段
 * 支持 multiMatch 多字段检索。status 保留（下架商品 status=0，搜索过滤；文档不删除便于重新上架）。</p>
 */
@Data
@Document(indexName = "citygo_product", createIndex = false)
public class ProductDoc {

    /** 商品ID（对应 MySQL product.id） */
    @Id
    private Long id;

    /** 所属店铺ID */
    @Field(type = FieldType.Long)
    private Long shopId;

    /** 店铺名称（冗余，列表展示） */
    @Field(type = FieldType.Keyword)
    private String shopName;

    /** 分类ID */
    @Field(type = FieldType.Long)
    private Long categoryId;

    /** 分类名称（冗余） */
    @Field(type = FieldType.Keyword)
    private String categoryName;

    /** 商品名称（IK 全文检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String productName;

    /** 商品描述（IK 全文检索） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    /** 售价 */
    @Field(type = FieldType.Double)
    private Double price;

    /** 销量（下单累加后同步，销量排序用） */
    @Field(type = FieldType.Integer)
    private Integer sales;

    /** 库存（索引保留；公开搜索返回时不暴露） */
    @Field(type = FieldType.Integer)
    private Integer stock;

    /** 状态：1 上架 0 下架 */
    @Field(type = FieldType.Integer)
    private Integer status;

}