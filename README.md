# CityGo

面向真实互联网业务场景设计的本地生活服务平台后端（参考美团核心业务，学习项目，非生产项目）。

## 技术栈版本矩阵

| 组件 | 版本 |
| --- | --- |
| Java (JDK) | 21.0.8 |
| Spring Boot | 4.1.1 |
| MyBatis-Plus | 3.5.17 |
| Springdoc (OpenAPI) | 3.1.0 |
| MySQL | 8.4.11 |
| Redis | 7 |
| RabbitMQ | 3 |
| Elasticsearch | 9.4.2 |
| Maven | 3.9.12 |
| Node | 24.11.1 |
| Docker | 29.4.0 |

## 当前进度

- **Phase 1（当前）**：项目初始化补全 —— 统一返回结构、全局异常处理、多环境配置、Swagger 文档、数据库/账号初始化、连通性验证接口。

## 快速启动

```bash
# 构建并运行测试
mvn clean test

# 启动应用（默认 dev 环境，端口 8080）
mvn spring-boot:run
```

## API 文档

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`