-- =============================================================================
-- CityGo 数据库初始化脚本
-- 仅创建新库与新账号，绝不触碰任何现有数据库 / 表 / 数据。
-- 目标：独立数据库 citygo + 专用账号 citygo_user（最小权限授予到 citygo 库）
-- =============================================================================

-- 创建业务库（若不存在），统一使用 utf8mb4 字符集
CREATE DATABASE IF NOT EXISTS citygo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建专用账号（若不存在），供应用连接使用
CREATE USER IF NOT EXISTS 'citygo_user'@'%' IDENTIFIED BY 'Citygo@2026';

-- 仅授予 citygo 库的全部权限，不影响其他库
GRANT ALL PRIVILEGES ON citygo.* TO 'citygo_user'@'%';

-- 刷新权限，使授权立即生效
FLUSH PRIVILEGES;