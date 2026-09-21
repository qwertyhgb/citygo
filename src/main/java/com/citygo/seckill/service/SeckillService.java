package com.citygo.seckill.service;

import com.citygo.product.vo.ProductVO;
import com.citygo.seckill.dto.SeckillConfigRequest;

/**
 * 秒杀业务服务接口。
 */
public interface SeckillService {

    /**
     * 商家配置秒杀（更新商品秒杀字段并初始化 Redis 预扣库存）。
     *
     * @param productId     商品ID
     * @param request       秒杀配置请求
     * @param currentUserId 当前登录商家用户ID
     * @return 更新后的商品视图
     */
    ProductVO configSeckill(Long productId, SeckillConfigRequest request, Long currentUserId);

    /**
     * 用户抢购秒杀商品（时段校验 → Redis 一人一单 → Redis 预扣库存 → MQ 异步下单）。
     *
     * @param productId 商品ID
     * @param userId    抢购用户ID
     */
    void seckill(Long productId, Long userId);

    /**
     * 查询秒杀抢购结果（前端轮询）。
     *
     * @param productId 秒杀商品ID
     * @param userId    抢购用户ID
     * @return 订单ID（已成功创建）；null（仍在排队处理中）
     */
    Long getSeckillResult(Long productId, Long userId);

}
