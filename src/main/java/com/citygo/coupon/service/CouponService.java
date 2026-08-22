package com.citygo.coupon.service;

import com.citygo.common.page.PageVO;
import com.citygo.coupon.dto.CouponCreateRequest;
import com.citygo.coupon.vo.CouponVO;
import com.citygo.coupon.vo.UserCouponVO;

import java.util.List;

/**
 * 优惠券域服务接口。
 */
public interface CouponService {

    /**
     * 商家创建券（校验店铺归属，只能为自家店铺创建商家券）。
     */
    CouponVO create(CouponCreateRequest request, Long currentUserId);

    /**
     * 可领券列表（公开）：仅 status=1 且在有效期内；可按 scope / shopId 过滤。
     */
    List<CouponVO> listPublic(Integer scope, Long shopId);

    /**
     * 领券：校验有效期/状态/防重复领，条件更新防超发，唯一索引兜底。
     */
    UserCouponVO claim(Long couponId, Long currentUserId);

    /**
     * 我的券分页（先惰性过期，再按状态过滤）。
     */
    PageVO<UserCouponVO> pageMy(Integer status, long pageNum, long pageSize, Long currentUserId);

}