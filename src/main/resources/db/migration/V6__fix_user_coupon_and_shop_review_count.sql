-- =============================================================================
-- V6__fix_user_coupon_and_shop_review_count.sql
-- 1. 修复 user_coupon 唯一键限制导致 per_user_limit > 1 无法领取多张的问题：
--    移除 uk_user_coupon 唯一键，改为普通联合索引 idx_user_coupon。
-- 2. shop 表增加 review_count 冗余字段（评价总数），避免列表/详情查询时每次全表 COUNT(*)。
-- =============================================================================

-- 1. 调整 user_coupon 表索引
ALTER TABLE `user_coupon` DROP INDEX `uk_user_coupon`;
ALTER TABLE `user_coupon` ADD KEY `idx_user_coupon` (`user_id`, `coupon_id`);

-- 2. shop 表增加 review_count 字段
ALTER TABLE `shop` ADD COLUMN `review_count` INT NOT NULL DEFAULT 0 COMMENT '评价总数' AFTER `score`;
