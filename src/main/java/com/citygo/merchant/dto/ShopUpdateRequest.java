package com.citygo.merchant.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 更新店铺请求体。
 *
 * <p>本阶段简化：除归属信息（merchantId）外，其余可编辑字段均支持更新。</p>
 */
@Data
public class ShopUpdateRequest {

    /** 店铺名称 */
    @Size(max = 50, message = "店铺名称长度不能超过 50")
    private String shopName;

    /** 店铺简介 */
    @Size(max = 500, message = "店铺简介长度不能超过 500")
    private String description;

    /** 市 */
    @Size(max = 50, message = "城市长度不能超过 50")
    private String city;

    /** 区 */
    @Size(max = 50, message = "区县长度不能超过 50")
    private String district;

    /** 详细地址 */
    @Size(max = 255, message = "详细地址长度不能超过 255")
    private String address;

    /** 经度 */
    private BigDecimal longitude;

    /** 纬度 */
    private BigDecimal latitude;

    /** 开始营业时间 */
    private LocalTime openTime;

    /** 结束营业时间 */
    private LocalTime closeTime;

    /** 店铺公告 */
    @Size(max = 500, message = "店铺公告长度不能超过 500")
    private String notice;

}