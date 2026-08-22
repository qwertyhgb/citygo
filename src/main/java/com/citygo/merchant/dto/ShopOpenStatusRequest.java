package com.citygo.merchant.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 切换店铺营业状态请求体。
 */
@Data
public class ShopOpenStatusRequest {

    /** 营业状态：1 营业中 0 打烊 */
    @NotNull(message = "营业状态不能为空")
    private Integer openStatus;

}