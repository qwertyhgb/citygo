package com.citygo.seckill.message;

import lombok.Data;

/**
 * 秒杀下单异步消息体。
 *
 * <p>用于在 Redis 预扣库存与一人一单去重成功后，投递到 RabbitMQ 进行异步订单创建削峰。</p>
 */
@Data
public class SeckillMessage {

    /** 抢购用户ID */
    private Long userId;

    /** 秒杀商品ID */
    private Long productId;

    public SeckillMessage() {
    }

    public SeckillMessage(Long userId, Long productId) {
        this.userId = userId;
        this.productId = productId;
    }

}
