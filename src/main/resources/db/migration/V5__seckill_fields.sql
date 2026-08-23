-- =============================================================================
-- V5__seckill_fields.sql
-- CityGo 秒杀场景支持字段扩展
--
-- 1. product 表扩展秒杀配置字段（秒杀价、秒杀库存、秒杀开始时间、秒杀结束时间）；
-- 2. orders 表扩展 source 字段（1 普通 2 秒杀）：
--    为什么加 source：
--    - 秒杀订单取消时回补秒杀库存（seckill_stock）与 Redis 预扣库存，而非普通库存；
--    - 用于秒杀消费者一人一单 DB 复查兜底（区分普通订单与秒杀订单）。
-- =============================================================================

ALTER TABLE product ADD COLUMN seckill_price DECIMAL(10,2) NULL COMMENT '秒杀价（NULL 表示不参与秒杀）' AFTER original_price;
ALTER TABLE product ADD COLUMN seckill_stock INT NULL COMMENT '秒杀库存（NULL 表示不参与秒杀）' AFTER seckill_price;
ALTER TABLE product ADD COLUMN seckill_start DATETIME NULL COMMENT '秒杀开始时间' AFTER seckill_stock;
ALTER TABLE product ADD COLUMN seckill_end DATETIME NULL COMMENT '秒杀结束时间' AFTER seckill_start;
ALTER TABLE orders ADD COLUMN source TINYINT NOT NULL DEFAULT 1 COMMENT '订单来源：1 普通 2 秒杀' AFTER status;
