package com.citygo.coupon.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户已领取优惠券视图对象。
 *
 * <p>在 user_coupon 基础上带出券模板关键信息（名称/类型/优惠字段），前端无需再查模板。</p>
 */
@Data
public class UserCouponVO {

    /** 用户券ID */
    private Long id;

    /** 券模板ID */
    private Long couponId;

    /** 优惠券名称 */
    private String couponName;

    /** 类型：1 满减 2 折扣 */
    private Integer type;

    /** 满减门槛（0 表示无门槛） */
    private BigDecimal thresholdAmount;

    /** 满减金额（type=1） */
    private BigDecimal discountAmount;

    /** 折扣率（type=2，85 表示 8.5 折） */
    private Integer discountRate;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 状态：1 未使用 2 已使用 3 已过期 */
    private Integer status;

}