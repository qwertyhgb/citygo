package com.citygo.coupon.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.coupon.dto.CouponCreateRequest;
import com.citygo.coupon.service.CouponService;
import com.citygo.coupon.vo.CouponVO;
import com.citygo.coupon.vo.UserCouponVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口：商家创建 / 公开可领列表 / 领券 / 我的券。
 */
@Tag(name = "优惠券", description = "优惠券创建、领取与我的券")
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 商家创建优惠券（仅商家，店铺归属校验在服务层）。
     */
    @Operation(summary = "创建优惠券")
    @PostMapping
    @PreAuthorize("hasRole('MERCHANT')")
    public Result<CouponVO> create(@Valid @RequestBody CouponCreateRequest request) {
        return Result.success(couponService.create(request, currentUserId()));
    }

    /**
     * 可领券列表（公开，无需登录）。
     */
    @Operation(summary = "可领券列表")
    @GetMapping
    public Result<List<CouponVO>> list(@RequestParam(required = false) Integer scope,
                                       @RequestParam(required = false) Long shopId) {
        return Result.success(couponService.listPublic(scope, shopId));
    }

    /**
     * 领券（需登录）。
     */
    @Operation(summary = "领券")
    @PostMapping("/{id}/claim")
    public Result<UserCouponVO> claim(@PathVariable Long id) {
        return Result.success(couponService.claim(id, currentUserId()));
    }

    /**
     * 我的券分页（需登录）。
     */
    @Operation(summary = "我的券")
    @GetMapping("/my")
    public Result<PageVO<UserCouponVO>> my(@RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(couponService.pageMy(status, pageNum, pageSize, currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}