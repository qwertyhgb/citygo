package com.citygo.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建收货地址请求体。
 */
@Data
public class AddressCreateRequest {

    /** 收货人姓名 */
    @NotBlank(message = "收货人不能为空")
    @Size(max = 50, message = "收货人长度不能超过 50")
    private String receiverName;

    /** 收货人电话 */
    @NotBlank(message = "电话不能为空")
    @Size(max = 20, message = "电话长度不能超过 20")
    private String receiverPhone;

    /** 省 */
    @NotBlank(message = "省份不能为空")
    @Size(max = 50, message = "省份长度不能超过 50")
    private String province;

    /** 市 */
    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市长度不能超过 50")
    private String city;

    /** 区 */
    @NotBlank(message = "区县不能为空")
    @Size(max = 50, message = "区县长度不能超过 50")
    private String district;

    /** 详细地址 */
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址长度不能超过 255")
    private String detailAddress;

    /** 是否默认地址：1 是 0 否（默认 0） */
    private Integer isDefault;

}