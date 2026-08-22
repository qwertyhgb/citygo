package com.citygo.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.coupon.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户已领取优惠券 Mapper，对应 {@code user_coupon} 表。
 *
 * <p>用于用户领券记录查询、下单核销（条件更新 status=1→2）、取消退券（条件更新 status=2→1）
 * 以及惰性过期（批量置 status=3）。唯一索引 uk_user_coupon（user_id, coupon_id）兜底防重复领取。</p>
 */
@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

}