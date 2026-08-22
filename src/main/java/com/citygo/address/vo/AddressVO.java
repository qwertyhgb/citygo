package com.citygo.address.vo;

import lombok.Data;

/**
 * 收货地址视图对象。
 */
@Data
public class AddressVO {

    /** 地址ID */
    private Long id;

    /** 收货人姓名 */
    private String receiverName;

    /** 收货人电话 */
    private String receiverPhone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区 */
    private String district;

    /** 详细地址 */
    private String detailAddress;

    /** 是否默认地址：1 是 0 否 */
    private Integer isDefault;

}