package com.citygo.order.dto;

import lombok.Data;

/**
 * 商家订单分页查询参数对象。
 *
 * <p>GET 请求的查询参数由 Spring 自动绑定到本对象（@ModelAttribute 默认行为），
 * 全部字段可空、有默认值。</p>
 */
@Data
public class MerchantOrderPageQuery {

    /** 店铺ID（可选，不传则查该商家所有店铺的订单；传入会校验店铺归属） */
    private Long shopId;

    /** 订单状态（可选，OrderStatus 的 code，如 10 待支付） */
    private Integer status;

    /** 页码（从 1 开始） */
    private long pageNum = 1;

    /** 每页条数 */
    private long pageSize = 10;
}
