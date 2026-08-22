package com.citygo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * CityGo 应用上下文启动测试。
 *
 * <p>使用 test profile 触发测试环境配置，加载整个 Spring 上下文，
 * 验证各组件（数据源、MyBatis-Plus、Springdoc 等）能正确装配、应用能正常启动。</p>
 *
 * <p>本测试同时隐式校验了到本机 MySQL 容器的连接配置是否可用
 * （dev / test 环境共用本地 citygo 数据库）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CityGoApplicationTests {

    @Test
    void contextLoads() {
        // 仅验证 Spring 上下文能够成功加载，无需任何断言体。
    }

}