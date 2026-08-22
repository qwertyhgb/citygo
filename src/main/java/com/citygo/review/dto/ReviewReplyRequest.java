package com.citygo.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家回复评价请求体。
 */
@Data
public class ReviewReplyRequest {

    /** 回复内容 */
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 500, message = "回复内容长度不能超过 500")
    private String content;

}