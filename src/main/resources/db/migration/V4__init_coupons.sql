-- =============================================================================
-- V4__init_coupons.sql
-- CityGo 平台优惠券种子数据
--
-- 本迁移由 Phase 9 优惠券系统任务单生成，仅向 coupon 表插入 2 张平台券。
-- 使用 INSERT IGNORE 配合主键 id（固定 1、2），保证脚本可重复执行。
--
-- 相对时间说明：
--   valid_start / valid_end 用 DATE_SUB / DATE_ADD 基于当前时间计算，
--   使这两张券"永远可用"，避免迁移执行时刻固化导致后启动的实例拿到过期券。
-- =============================================================================

INSERT IGNORE INTO `coupon`
(`id`, `coupon_name`, `type`, `threshold_amount`, `discount_amount`, `discount_rate`,
 `total_count`, `received_count`, `per_user_limit`, `valid_start`, `valid_end`,
 `scope`, `shop_id`, `status`)
VALUES
    -- 平台满减券：满 100 减 20，全场可用
    (1, '平台满减券', 1, 100.00, 20.00, NULL,
     1000, 0, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 YEAR),
     1, NULL, 1),
    -- 新人无门槛券：0 门槛减 5 元
    (2, '新人无门槛券', 1, 0.00, 5.00, NULL,
     1000, 0, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 YEAR),
     1, NULL, 1);