package com.citygo.shop.dto;

import lombok.Data;

/**
 * 店铺浏览列表查询参数对象。
 *
 * <p>GET 请求的查询参数由 Spring 自动绑定到本对象（@ModelAttribute 默认行为），
 * 全部字段可空，仅对取值范围做约束。</p>
 */
@Data
public class ShopBrowseQuery {

    /** 城市（可选，精确匹配） */
    private String city;

    /** 关键词（可选，店铺名模糊匹配） */
    private String keyword;

    /** 排序方式：default 新店优先 | score 评分优先 | sales 销量优先；默认 default */
    private String sort;

    /** 页码（从 1 开始） */
    private long pageNum = 1;

    /** 每页条数（1-50） */
    private long pageSize = 10;

}