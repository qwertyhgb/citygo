package com.citygo.review.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价视图对象。
 *
 * <p>带出评价人昵称/头像、店铺名（便于列表直接展示），以及商家的回复与回复时间。</p>
 */
@Data
public class ReviewVO {

    /** 评价ID */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 店铺ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 评价用户ID */
    private Long userId;

    /** 评价人昵称 */
    private String nickname;

    /** 评价人头像 */
    private String avatar;

    /** 评分 1-5 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 评价图片（逗号分隔 URL） */
    private String images;

    /** 商家回复 */
    private String merchantReply;

    /** 回复时间 */
    private LocalDateTime replyTime;

    /** 创建时间 */
    private LocalDateTime createTime;

}