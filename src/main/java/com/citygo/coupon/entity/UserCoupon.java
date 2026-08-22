package com.citygo.coupon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户已领取优惠券实体，对应数据库 {@code user_coupon} 表。
 *
 * <p>记录某用户领取的某张券实例；{@code orderId} 在有核销时关联订单、
 * 退券时清空；{@code expireTime} 冗余自模板 valid_end，便于惰性过期与查询。</p>
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 券模板ID */
    private Long couponId;

    /** 核销时关联的订单ID */
    private Long orderId;

    /** 状态：1 未使用 2 已使用 3 已过期 */
    private Integer status;

    /** 领取时间 */
    private LocalDateTime receivedTime;

    /** 使用时间 */
    private LocalDateTime usedTime;

    /** 过期时间（= 模板 valid_end，冗余方便查询） */
    private LocalDateTime expireTime;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}