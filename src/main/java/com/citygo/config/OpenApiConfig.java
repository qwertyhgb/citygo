package com.citygo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 文档配置。
 *
 * <p>springdoc 会在应用启动时扫描所有 {@code @RestController}，结合方法上的
 * {@code @Tag}、{@code @Operation} 等注解自动生成 OpenAPI 3 规范文档，
 * 并提供开箱即用的 Swagger UI 页面。</p>
 *
 * <p>文档访问地址：</p>
 * <ul>
 *   <li>JSON 规范：{@code /v3/api-docs}</li>
 *   <li>调试页面：{@code /swagger-ui.html}</li>
 * </ul>
 *
 * @author citygo
 */
@Configuration
public class OpenApiConfig {

    /**
     * 声明 OpenAPI 实例，配置文档的基础信息（标题、版本、说明）。
     */
    @Bean
    public OpenAPI citygoOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("CityGo API")
                .version("0.1.0")
                .description("CityGo 本地生活服务平台接口文档"));
    }

}