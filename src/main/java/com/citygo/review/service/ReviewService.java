package com.citygo.review.service;

import com.citygo.common.page.PageVO;
import com.citygo.review.dto.ReviewCreateRequest;
import com.citygo.review.dto.ReviewReplyRequest;
import com.citygo.review.vo.ReviewVO;

/**
 * 评价域服务接口。
 */
public interface ReviewService {

    /**
     * 发表评价（校验订单归属与状态、防重复；联动重算店铺评分并失效店铺详情缓存）。
     */
    ReviewVO create(ReviewCreateRequest request, Long currentUserId);

    /**
     * 商家回复评价（校验评价所属店铺归属当前商家）。
     */
    ReviewVO reply(Long reviewId, ReviewReplyRequest request, Long currentUserId);

    /**
     * 店铺评价列表（公开，仅 status=1，按 id 倒序）。
     */
    PageVO<ReviewVO> listShop(Long shopId, long pageNum, long pageSize);

    /**
     * 我的评价分页（按 id 倒序）。
     */
    PageVO<ReviewVO> pageMy(long pageNum, long pageSize, Long currentUserId);

}