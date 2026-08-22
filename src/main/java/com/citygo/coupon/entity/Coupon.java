package com.citygo.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板实体，对应数据库 {@code coupon} 表。
 *
 * <p>由平台或商家发布的券模板，{@code received_count} 为已领取数（领取时累加）；
 * {@code scope} 区分平台券/商家券，商家券需在 {@code shopId} 指定的店铺内使用。</p>
 */
@Data
@TableName("coupon")
public class Coupon {

    /** 主键（雪花ID；种子券固定 1、2） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 优惠券名称 */
    private String couponName;

    /** 类型：1 满减券 2 折扣券 */
    private Integer type;

    /** 满减门槛（满多少可用，0 表示无门槛） */
    private BigDecimal thresholdAmount;

    /** 满减金额（type=1 时使用） */
    private BigDecimal discountAmount;

    /** 折扣率（type=2 时使用，85 表示 8.5 折） */
    private Integer discountRate;

    /** 发行总量 */
    private Integer totalCount;

    /** 已领取数量（领取时累加，用于防超发） */
    private Integer receivedCount;

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

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}