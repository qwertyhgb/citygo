package com.citygo.mq.message;

import lombok.Data;

/**
 * 库存预警内容（写进 Redis List 的 JSON 载荷）。
 *
 * <p>这是"预警通道"内部载荷，后续可能对接监控/告警系统，字段按展示需要设计。</p>
 */
@Data
public class StockWarnMessage {

    /** 商品ID */
    private Long productId;

    /** 商品名称 */
    private String productName;

    /** 当前剩余库存 */
    private Integer stock;

    public StockWarnMessage() {
    }

    public StockWarnMessage(Long productId, String productName, Integer stock) {
        this.productId = productId;
        this.productName = productName;
        this.stock = stock;
    }

}