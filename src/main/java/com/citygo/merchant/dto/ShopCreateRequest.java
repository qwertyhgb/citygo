package com.citygo.merchant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 创建店铺请求体。
 */
@Data
public class ShopCreateRequest {

    /** 店铺名称 */
    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 50, message = "店铺名称长度不能超过 50")
    private String shopName;

    /** 市 */
    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市长度不能超过 50")
    private String city;

    /** 区 */
    @Size(max = 50, message = "区县长度不能超过 50")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址长度不能超过 255")
    private String address;

    /** 经度（可空） */
    @DecimalMin(value = "-180", message = "经度不能小于 -180")
    @DecimalMax(value = "180", message = "经度不能大于 180")
    private BigDecimal longitude;

    /** 纬度（可空） */
    @DecimalMin(value = "-90", message = "纬度不能小于 -90")
    @DecimalMax(value = "90", message = "纬度不能大于 90")
    private BigDecimal latitude;

    /** 开始营业时间（可空） */
    private LocalTime openTime;

    /** 结束营业时间（可空） */
    private LocalTime closeTime;

    /** 店铺公告 */
    @Size(max = 500, message = "店铺公告长度不能超过 500")
    private String notice;

}