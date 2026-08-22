package com.citygo.review.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.review.dto.ReviewCreateRequest;
import com.citygo.review.dto.ReviewReplyRequest;
import com.citygo.review.service.ReviewService;
import com.citygo.review.vo.ReviewVO;
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

/**
 * 评价接口：发表评价 / 商家回复 / 我的评价。
 *
 * <p>公开的"店铺评价列表"在单独的 {@link ShopReviewController}（路径 /api/shops/{id}/reviews，
 * 天然被 SecurityConfig 的 GET /api/shops/** permitAll 放行）。</p>
 */
@Tag(name = "评价", description = "订单评价、商家回复与我的评价")
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 发表评价（需登录，仅已完成订单，一单一评）。
     */
    @Operation(summary = "发表评价")
    @PostMapping
    public Result<ReviewVO> create(@Valid @RequestBody ReviewCreateRequest request) {
        return Result.success(reviewService.create(request, currentUserId()));
    }

    /**
     * 商家回复评价（仅商家，归属校验在服务层）。
     */
    @Operation(summary = "商家回复")
    @PostMapping("/{id}/reply")
    @PreAuthorize("hasRole('MERCHANT')")
    public Result<ReviewVO> reply(@PathVariable Long id, @Valid @RequestBody ReviewReplyRequest request) {
        return Result.success(reviewService.reply(id, request, currentUserId()));
    }

    /**
     * 我的评价分页（需登录）。
     */
    @Operation(summary = "我的评价")
    @GetMapping("/my")
    public Result<PageVO<ReviewVO>> my(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(reviewService.pageMy(pageNum, pageSize, currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}