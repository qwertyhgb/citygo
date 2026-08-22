package com.citygo.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评价请求体。
 */
@Data
public class ReviewCreateRequest {

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 评分 1-5 星 */
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer rating;

    /** 评价内容（可空） */
    @Size(max = 1000, message = "评价内容长度不能超过 1000")
    private String content;

    /** 评价图片（可空，逗号分隔 URL） */
    @Size(max = 500, message = "评价图片描述长度不能超过 500")
    private String images;

}