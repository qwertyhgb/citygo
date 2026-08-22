package com.citygo.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 店铺实体，对应数据库 {@code shop} 表。
 *
 * <p>一个商家可开多个店铺，通过 {@code merchantId} 与 merchant 表逻辑关联；
 * {@code monthlySales} 为冗余字段（下单时累加）。</p>
 */
@Data
@TableName("shop")
public class Shop {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属商家ID */
    private Long merchantId;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo */
    private String logo;

    /** 店铺简介 */
    private String description;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区 */
    private String district;

    /** 详细地址 */
    private String address;

    /** 经度 */
    private BigDecimal longitude;

    /** 纬度 */
    private BigDecimal latitude;

    /** 评分（0-5） */
    private BigDecimal score;

    /** 月销量（冗余字段） */
    private Integer monthlySales;

    /** 营业状态：1 营业中 0 打烊 */
    private Integer openStatus;

    /** 开始营业时间 */
    private LocalTime openTime;

    /** 结束营业时间 */
    private LocalTime closeTime;

    /** 店铺公告 */
    private String notice;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}