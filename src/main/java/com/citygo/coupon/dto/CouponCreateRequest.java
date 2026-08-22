package com.citygo.coupon.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家创建优惠券请求体。
 *
 * <p>满减券（type=1）与折扣券（type=2）的字段是互斥的：
 * type=1 传 discountAmount、type=2 传 discountRate，二者只在对应类型下必传，
 * 具体互斥校验放在服务层按 type 分支判断。</p>
 */
@Data
public class CouponCreateRequest {

    /** 优惠券名称 */
    @NotBlank(message = "优惠券名称不能为空")
    @Size(max = 100, message = "优惠券名称长度不能超过 100")
    private String couponName;

    /** 类型：1 满减 2 折扣 */
    @NotNull(message = "优惠券类型不能为空")
    private Integer type;

    /** 满减门槛（满多少可用，0 表示无门槛） */
    @NotNull(message = "使用门槛金额不能为空")
    @DecimalMin(value = "0", message = "使用门槛不能为负数")
    private BigDecimal thresholdAmount;

    /** 满减金额（type=1 必传） */
    @DecimalMin(value = "0.01", message = "满减金额必须大于 0")
    private BigDecimal discountAmount;

    /** 折扣率（type=2 必传，85 表示 8.5 折） */
    @Min(value = 1, message = "折扣率最小为 1")
    @Max(value = 99, message = "折扣率最大为 99")
    private Integer discountRate;

    /** 发行总量 */
    @NotNull(message = "发行总量不能为空")
    @Min(value = 1, message = "发行总量至少为 1")
    private Integer totalCount;

    /** 每人限领数量（默认 1） */
    @Min(value = 1, message = "每人限领数量至少为 1")
    private Integer perUserLimit = 1;

    /** 有效期开始 */
    @NotNull(message = "有效期开始时间不能为空")
    private LocalDateTime validStart;

    /** 有效期结束（必须晚于开始时间，由下方 @AssertTrue 校验） */
    @NotNull(message = "有效期结束时间不能为空")
    private LocalDateTime validEnd;

    /** 适用范围（商家只能创建商家券） */
    @NotNull(message = "券适用范围不能为空")
    private Integer scope;

    /** 商家券绑定的店铺ID */
    @NotNull(message = "商家券必须指定店铺")
    private Long shopId;

    /**
     * 有效期结束必须晚于开始；不满足时返回校验失败文案。
     */
    @AssertTrue(message = "有效期结束时间必须晚于开始时间")
    public boolean isValidRange() {
        if (validStart == null || validEnd == null) {
            return true; // 由 @NotNull 负责校验为空
        }
        return validEnd.isAfter(validStart);
    }

}