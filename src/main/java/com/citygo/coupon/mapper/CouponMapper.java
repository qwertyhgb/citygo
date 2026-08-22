package com.citygo.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.coupon.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;

/**
 * 优惠券模板 Mapper，对应 {@code coupon} 表。
 *
 * <p>用于券模板的增删改查、可领券列表查询；领券的超发控制用
 * {@code SELECT ... WHERE received_count < total_count} 这类条件更新完成。</p>
 */
@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

}