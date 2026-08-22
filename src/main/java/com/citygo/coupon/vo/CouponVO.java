package com.citygo.coupon.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板视图对象。
 *
 * <p>在模板基础字段上补充 {@code remainingCount}（剩余可领数量），便于列表直接展示。</p>
 */
@Data
public class CouponVO {

    /** 券ID */
    private Long id;

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

    /** 发行总量 */
    private Integer totalCount;

    /** 已领取数量 */
    private Integer receivedCount;

    /** 剩余可领数量 = total_count - received_count */
    private Integer remainingCount;

    /** 每人限领数量 */
    private Integer perUserLimit;

    /** 有效期开始 */
    private LocalDateTime validStart;

    /** 有效期结束 */
    private LocalDateTime validEnd;

    /** 适用范围：1 平台券 2 商家券 */
    private Integer scope;

    /** scope=2 时指定店铺 */
    private Long shopId;

    /** 状态：1 可领取 0 停发 */
    private Integer status;

}