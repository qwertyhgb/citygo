-- =============================================================================
-- V7__seckill_message.sql
-- CityGo 秒杀可靠消息：本地消息表
--
-- 背景：秒杀链路 Redis 预扣成功后直接 convertAndSend 发 MQ，若发送失败会形成
--       "Redis 已扣库存、MQ 无消息、DB 无订单"的可靠性缝隙（订单凭空丢失）。
-- 方案：抢购资格以【本地消息表落库】为准——Lua 预扣成功后先落一条 status=0 的消息，
--       再尝试投递 MQ；投递成功置 status=1，失败保持 status=0 由定时补偿任务
--       （SeckillMessageRelayTask）重发，配合消费者 hasPurchasedInDB 幂等复查，
--       彻底填平 Redis → MQ 之间的缝隙（可靠消息最终一致性）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `seckill_message` (
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `user_id`         BIGINT       NOT NULL COMMENT '抢购用户ID',
    `product_id`      BIGINT       NOT NULL COMMENT '秒杀商品ID',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 待发送 1 已发送 2 重试超限（人工介入）',
    `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '已补偿重试次数',
    `next_retry_time` DATETIME     NOT NULL COMMENT '下次补偿重试时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_next_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '秒杀可靠消息本地消息表';
