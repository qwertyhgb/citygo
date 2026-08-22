package com.citygo.search.doc;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

/**
 * 店铺搜索文档（索引 {@code citygo_shop}）。
 *
 * <p>ES 索引里<b>冗余展示字段</b>用于搜索与排序，搜索时不再查 MySQL。
 * 索引名统一 {@code citygo_} 前缀，与 knowflow 项目的索引隔离。
 * IK 分词：shopName 用 {@code ik_max_word}（索引最细切分，召回全）+
 * {@code ik_smart}（查询智能切分，结果准）。</p>
 */
@Data
@Document(indexName = "citygo_shop", createIndex = false)
public class ShopDoc {

    /** 店铺ID（对应 MySQL shop.id，作为 ES 文档主键） */
    @Id
    private Long id;

    /** 店铺名称（IK 全文检索字段） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    /** 城市（精确匹配过滤） */
    @Field(type = FieldType.Keyword)
    private String city;

    /** 区县 */
    @Field(type = FieldType.Keyword)
    private String district;

    /** 详细地址 */
    @Field(type = FieldType.Keyword)
    private String address;

    /** 评分（重算后同步，与 MySQL shop.score 一致） */
    @Field(type = FieldType.Double)
    private Double score;

    /** 月销量（下单累加后同步） */
    @Field(type = FieldType.Integer)
    private Integer monthlySales;

    /** 营业状态：1 营业中 0 打烊 */
    @Field(type = FieldType.Integer)
    private Integer openStatus;

    /** 状态：1 正常 0 禁用 */
    @Field(type = FieldType.Integer)
    private Integer status;

    /** 经纬度（geo_point，[经度,纬度] 由 GeoPoint(lat,lon) 映射，用于附近店铺距离查询/排序） */
    @GeoPointField
    private GeoPoint location;

}