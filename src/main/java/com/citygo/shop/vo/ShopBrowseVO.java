package com.citygo.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 用户端店铺浏览视图对象。
 *
 * <p>字段与商家侧 ShopVO 一致；浏览侧单独建一份，避免商家管理接口与用户浏览接口
 * 因复用同一 VO 而互相耦合（后续任一侧字段演进互不影响）。</p>
 */
@Data
public class ShopBrowseVO {

    /** 店铺ID */
    private Long id;

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

    /** 评价总数 */
    private Integer reviewCount;

    /** 月销量 */
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

    /** 创建时间 */
    private LocalDateTime createTime;

}