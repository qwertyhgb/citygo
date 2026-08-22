package com.citygo.review.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.review.service.ReviewService;
import com.citygo.review.vo.ReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺评价公开列表控制器。
 *
 * <p>映射到 {@code /api/shops/{id}/reviews}。之所以单独建控制器而非并入
 * {@link ReviewController}（其类级前缀是 /api/reviews），是为了让公开列表
 * 稳定挂在 /api/shops/** 下——该前缀已被 SecurityConfig 的
 * {@code GET /api/shops/**} permitAll 放行，无需额外配置安全规则。</p>
 */
@Tag(name = "评价", description = "店铺评价公开列表")
@RestController
@RequestMapping("/api/shops/{id}")
public class ShopReviewController {

    private final ReviewService reviewService;

    public ShopReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 店铺评价列表（公开，仅 status=1，分页 + 组装昵称/头像/店铺名）。
     */
    @Operation(summary = "店铺评价列表（公开）")
    @GetMapping("/reviews")
    public Result<PageVO<ReviewVO>> list(@PathVariable Long id,
                                         @RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(reviewService.listShop(id, pageNum, pageSize));
    }

}