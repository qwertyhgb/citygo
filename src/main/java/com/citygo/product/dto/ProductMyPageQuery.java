package com.citygo.product.dto;

import lombok.Data;

/**
 * 商家"我的商品"分页查询参数对象。
 *
 * <p>GET 请求的查询参数由 Spring 自动绑定到本对象（@ModelAttribute 默认行为），
 * 全部字段可空、有默认值。</p>
 */
@Data
public class ProductMyPageQuery {

    /** 店铺ID（可选，不传则查该商家所有店铺的商品） */
    private Long shopId;

    /** 关键词（可选，商品名模糊匹配） */
    private String keyword;

    /** 页码（从 1 开始） */
    private long pageNum = 1;

    /** 每页条数（1-50，Service 层会夹逼收口） */
    private long pageSize = 10;
}