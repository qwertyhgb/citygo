-- =============================================================================
-- V1__init_schema.sql
-- CityGo 数据库结构初始化
-- 本迁移由 Phase 2 数据库设计任务单生成，包含 CityGo 全部 14 张业务表。
--
-- 通用设计约定：
--   * 存储引擎 InnoDB，字符集 utf8mb4，排序规则 utf8mb4_unicode_ci
--   * 主键 id 使用 BIGINT，不使用 AUTO_INCREMENT，后续由 MyBatis-Plus 雪花算法
--     生成，分布式友好、全局唯一。
--   * 每张表末尾都包含三个公共字段：create_time / update_time / deleted（逻辑删除）。
--   * 不使用物理外键，跨表关系用"逻辑外键 + 索引"表达，避免删除/更新耦合。
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 用户表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密存储）',
    `phone`       VARCHAR(20)  NULL     COMMENT '手机号（可空，预留）',
    `nickname`    VARCHAR(50)  NULL     COMMENT '昵称',
    `avatar`      VARCHAR(255) NULL     COMMENT '头像 URL',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- ---------------------------------------------------------------------------
-- 2. 角色表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `role` (
    `id`          BIGINT       NOT NULL COMMENT '主键',
    `code`        VARCHAR(30)  NOT NULL COMMENT '角色编码',
    `name`        VARCHAR(50)  NOT NULL COMMENT '角色名称',
    `description` VARCHAR(255) NULL     COMMENT '角色描述',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色表';

-- ---------------------------------------------------------------------------
-- 3. 用户角色关联表（一个用户可拥有多个角色）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_role` (
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户角色关联表';

-- ---------------------------------------------------------------------------
-- 4. 商家表（平台身份资料，与 user 表通过 user_id 逻辑关联）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `merchant` (
    `id`            BIGINT       NOT NULL COMMENT '主键',
    `user_id`       BIGINT       NOT NULL COMMENT '关联用户ID',
    `merchant_name` VARCHAR(100) NOT NULL COMMENT '商家名称',
    `contact_name`  VARCHAR(50)  NOT NULL COMMENT '联系人姓名',
    `contact_phone` VARCHAR(20)  NOT NULL COMMENT '联系电话',
    `logo`          VARCHAR(255) NULL     COMMENT '商家 logo',
    `description`   VARCHAR(500) NULL     COMMENT '商家简介',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_contact_phone` (`contact_phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商家表';

-- ---------------------------------------------------------------------------
-- 5. 店铺表（一个商家可开多个店铺）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `shop` (
    `id`             BIGINT       NOT NULL COMMENT '主键',
    `merchant_id`    BIGINT       NOT NULL COMMENT '所属商家ID',
    `shop_name`      VARCHAR(100) NOT NULL COMMENT '店铺名称',
    `logo`           VARCHAR(255) NULL     COMMENT '店铺 logo',
    `description`    VARCHAR(500) NULL     COMMENT '店铺简介',
    `province`       VARCHAR(50)  NULL     COMMENT '省',
    `city`           VARCHAR(50)  NOT NULL COMMENT '市',
    `district`       VARCHAR(50)  NULL     COMMENT '区',
    `address`        VARCHAR(255) NOT NULL COMMENT '详细地址',
    `longitude`      DECIMAL(10, 6) NULL   COMMENT '经度',
    `latitude`       DECIMAL(10, 6) NULL   COMMENT '纬度',
    `score`          DECIMAL(2, 1) NOT NULL DEFAULT 0.0 COMMENT '评分（0-5，后续由评价计算）',
    `monthly_sales`  INT          NOT NULL DEFAULT 0 COMMENT '月销量（冗余字段，下单时累加）',
    `open_status`    TINYINT      NOT NULL DEFAULT 1 COMMENT '营业状态：1 营业中 0 打烊',
    `open_time`      TIME         NULL     COMMENT '开始营业时间',
    `close_time`     TIME         NULL     COMMENT '结束营业时间',
    `notice`         VARCHAR(500) NULL     COMMENT '店铺公告',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_merchant_id` (`merchant_id`),
    KEY `idx_city_district` (`city`, `district`),
    KEY `idx_score` (`score`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '店铺表';

-- ---------------------------------------------------------------------------
-- 6. 商品分类表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
    `id`            BIGINT      NOT NULL COMMENT '主键',
    `parent_id`     BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID，0 表示一级分类',
    `category_name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `icon`          VARCHAR(255) NULL    COMMENT '分类图标',
    `sort`          INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`        TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品分类表';

-- ---------------------------------------------------------------------------
-- 7. 商品表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `product` (
    `id`             BIGINT        NOT NULL COMMENT '主键',
    `shop_id`        BIGINT        NOT NULL COMMENT '所属店铺ID',
    `category_id`    BIGINT        NOT NULL COMMENT '分类ID',
    `product_name`   VARCHAR(100)  NOT NULL COMMENT '商品名称',
    `description`    VARCHAR(1000) NULL     COMMENT '商品描述',
    `cover_image`    VARCHAR(255)  NULL     COMMENT '主图 URL',
    `images`         TEXT          NULL     COMMENT '轮播图（逗号分隔多个 URL）',
    `price`          DECIMAL(10, 2) NOT NULL COMMENT '售价',
    `original_price` DECIMAL(10, 2) NULL    COMMENT '原价（划线价）',
    `stock`          INT           NOT NULL DEFAULT 0 COMMENT '库存',
    `sales`          INT           NOT NULL DEFAULT 0 COMMENT '销量（冗余字段，下单时累加）',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 上架 0 下架',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_shop_status` (`shop_id`, `status`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_price` (`price`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '商品表';

-- ---------------------------------------------------------------------------
-- 8. 订单表
-- 注意：表名用 orders（order 是 MySQL 保留字）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `orders` (
    `id`               BIGINT        NOT NULL COMMENT '主键',
    `order_no`         VARCHAR(32)   NOT NULL COMMENT '业务订单号（雪花号，全局唯一）',
    `user_id`          BIGINT        NOT NULL COMMENT '下单用户ID',
    `shop_id`          BIGINT        NOT NULL COMMENT '店铺ID',
    `total_amount`     DECIMAL(10, 2) NOT NULL COMMENT '商品总额',
    `discount_amount`  DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
    `pay_amount`       DECIMAL(10, 2) NOT NULL COMMENT '实付金额',
    `status`           TINYINT       NOT NULL DEFAULT 10 COMMENT '订单状态：10 待支付 20 已支付 30 商家已接单 40 配送中 50 已完成 60 已取消 70 退款中 80 已退款',
    `receiver_name`    VARCHAR(50)   NOT NULL COMMENT '收货人姓名（下单时快照）',
    `receiver_phone`   VARCHAR(20)   NOT NULL COMMENT '收货人电话（快照）',
    `receiver_address` VARCHAR(255)  NOT NULL COMMENT '收货地址（快照）',
    `remark`           VARCHAR(255)  NULL     COMMENT '买家备注',
    `paid_time`        DATETIME      NULL     COMMENT '支付时间',
    `completed_time`   DATETIME      NULL     COMMENT '完成时间',
    `cancel_time`      DATETIME      NULL     COMMENT '取消时间',
    `cancel_reason`    VARCHAR(255)  NULL     COMMENT '取消原因',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '订单表';

-- ---------------------------------------------------------------------------
-- 9. 订单明细表（商品信息全部快照，防止改价/删除后订单数据失真）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_item` (
    `id`            BIGINT        NOT NULL COMMENT '主键',
    `order_id`      BIGINT        NOT NULL COMMENT '订单ID',
    `product_id`    BIGINT        NOT NULL COMMENT '商品ID',
    `product_name`  VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `product_image` VARCHAR(255)  NULL     COMMENT '商品主图（快照）',
    `price`         DECIMAL(10, 2) NOT NULL COMMENT '成交单价（快照）',
    `quantity`      INT           NOT NULL COMMENT '购买数量',
    `total_amount`  DECIMAL(10, 2) NOT NULL COMMENT '小计金额',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '订单明细表';

-- ---------------------------------------------------------------------------
-- 10. 支付记录表
-- 一单多次支付尝试只允许一条成功记录，靠唯一索引兜底防重复支付
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `payment` (
    `id`          BIGINT        NOT NULL COMMENT '主键',
    `payment_no`  VARCHAR(32)   NOT NULL COMMENT '支付流水号（全局唯一）',
    `order_id`    BIGINT        NOT NULL COMMENT '订单ID',
    `user_id`     BIGINT        NOT NULL COMMENT '支付用户ID',
    `amount`      DECIMAL(10, 2) NOT NULL COMMENT '支付金额',
    `pay_method`  TINYINT       NOT NULL DEFAULT 1 COMMENT '支付方式：1 微信 2 支付宝 3 模拟支付',
    `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 待支付 2 支付成功 3 支付失败 4 已退款',
    `paid_time`   DATETIME      NULL     COMMENT '支付成功时间',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '支付记录表';

-- ---------------------------------------------------------------------------
-- 11. 优惠券模板表（平台/商家发布的券模板）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coupon` (
    `id`               BIGINT        NOT NULL COMMENT '主键',
    `coupon_name`      VARCHAR(100)  NOT NULL COMMENT '优惠券名称',
    `type`             TINYINT       NOT NULL COMMENT '类型：1 满减券 2 折扣券',
    `threshold_amount` DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '满减门槛（满多少可用，0 表示无门槛）',
    `discount_amount`  DECIMAL(10, 2) NULL    COMMENT '满减金额（type=1 时使用）',
    `discount_rate`    TINYINT       NULL     COMMENT '折扣率（type=2 时使用，85 表示 8.5 折）',
    `total_count`      INT           NOT NULL COMMENT '发行总量',
    `received_count`   INT           NOT NULL DEFAULT 0 COMMENT '已领取数量（领取时累加）',
    `per_user_limit`   INT           NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    `valid_start`      DATETIME      NOT NULL COMMENT '有效期开始',
    `valid_end`        DATETIME      NOT NULL COMMENT '有效期结束',
    `scope`            TINYINT       NOT NULL DEFAULT 1 COMMENT '适用范围：1 平台券 2 商家券',
    `shop_id`          BIGINT        NULL     COMMENT 'scope=2 时指定店铺',
    `status`           TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 可领取 0 停发',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_scope_shop` (`scope`, `shop_id`),
    KEY `idx_valid_end` (`valid_end`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '优惠券模板表';

-- ---------------------------------------------------------------------------
-- 12. 用户已领取优惠券表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_coupon` (
    `id`            BIGINT      NOT NULL COMMENT '主键',
    `user_id`       BIGINT      NOT NULL COMMENT '用户ID',
    `coupon_id`     BIGINT      NOT NULL COMMENT '券模板ID',
    `order_id`      BIGINT      NULL     COMMENT '核销时关联的订单ID',
    `status`        TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 未使用 2 已使用 3 已过期',
    `received_time` DATETIME    NOT NULL COMMENT '领取时间',
    `used_time`     DATETIME    NULL     COMMENT '使用时间',
    `expire_time`   DATETIME    NOT NULL COMMENT '过期时间（= 模板 valid_end，冗余方便查询）',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_coupon` (`user_id`, `coupon_id`),
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_coupon_id` (`coupon_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户已领取优惠券表';

-- ---------------------------------------------------------------------------
-- 13. 评价表（一单一条评价，针对店铺整体）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review` (
    `id`              BIGINT        NOT NULL COMMENT '主键',
    `order_id`        BIGINT        NOT NULL COMMENT '订单ID',
    `user_id`         BIGINT        NOT NULL COMMENT '评价用户ID',
    `shop_id`         BIGINT        NOT NULL COMMENT '店铺ID',
    `rating`          TINYINT       NOT NULL COMMENT '评分 1-5',
    `content`         VARCHAR(1000) NULL     COMMENT '评价内容',
    `images`          TEXT          NULL     COMMENT '评价图片（逗号分隔）',
    `merchant_reply`  VARCHAR(1000) NULL     COMMENT '商家回复',
    `reply_time`      DATETIME      NULL     COMMENT '回复时间',
    `status`          TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 展示 0 隐藏',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_shop_rating` (`shop_id`, `rating`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '评价表';

-- ---------------------------------------------------------------------------
-- 14. 收货地址表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `address` (
    `id`             BIGINT       NOT NULL COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '用户ID',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货人电话',
    `province`       VARCHAR(50)  NOT NULL COMMENT '省',
    `city`           VARCHAR(50)  NOT NULL COMMENT '市',
    `district`       VARCHAR(50)  NOT NULL COMMENT '区',
    `detail_address` VARCHAR(255) NOT NULL COMMENT '详细地址',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：1 是 0 否',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '收货地址表';