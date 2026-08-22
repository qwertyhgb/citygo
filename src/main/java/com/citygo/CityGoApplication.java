package com.citygo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CityGo 后端入口。
 *
 * <p>CityGo 是一个面向真实互联网业务场景设计的本地生活服务平台（参考美团核心业务，学习项目）。
 * 本阶段（Phase 1）以单体应用起步，仅完成项目初始化与基础设施搭建，不包含业务功能。
 * 后续阶段陆续加入用户认证、商家、店铺、商品、订单等业务模块。</p>
 *
 * <p>通过 {@code @MapperScan} 统一扫描各业务包（com.citygo.**.mapper）下的 MyBatis Mapper 接口，
 * 避免逐个 {@code @Mapper} 注册；各业务阶段新增的 Mapper 只需放到对应包即可被自动扫描到。</p>
 *
 * <p>后续演进规划：随着业务扩展，将按业务边界（auth / user / merchant / shop / product /
 * category / order / payment / coupon / review / search / system 等领域）逐步拆分，
 * 最终演进为微服务架构。届时每个模块可独立发布与部署。</p>
 *
 * @author citygo
 */
@SpringBootApplication
@MapperScan("com.citygo.**.mapper")
public class CityGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityGoApplication.class, args);
    }

}