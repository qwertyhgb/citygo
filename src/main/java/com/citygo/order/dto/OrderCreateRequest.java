package com.citygo.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建订单请求体。
 */
@Data
public class OrderCreateRequest {

    /** 收货地址ID */
    @NotNull(message = "地址ID不能为空")
    private Long addressId;

    /** 买家备注 */
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;

    /** 用户已领取的优惠券ID（用户券 id，可空：不使用优惠券） */
    private Long couponId;

    /** 下单商品明细（至少一项） */
    @NotEmpty(message = "订单至少包含一个商品")
    @Valid
    private List<OrderItemRequest> items;

}