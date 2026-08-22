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

- **Phase 2（当前）**：数据库表结构设计与 Flyway 迁移 —— 14 张业务表（V1 建表 + V2 角色种子数据）、数据库设计文档。
- **Phase 1**：项目初始化补全 —— 统一返回结构、全局异常处理、多环境配置、Swagger 文档、数据库/账号初始化、连通性验证接口。

## 快速启动

```bash
# 构建并运行测试
mvn clean test

# 启动应用（默认 dev 环境，端口 8080）
mvn spring-boot:run
```

## 数据库

CityGo 使用 **Flyway** 管理数据库结构迁移，应用启动时自动按版本号顺序执行迁移脚本，并把执行记录写入 `flyway_schema_history` 表，保证 dev / test / prod 环境结构一致。当前共 **14 张业务表**。

**新增迁移文件规则**：
- 命名：`src/main/resources/db/migration/V{版本}__{描述}.sql`，其中 `{版本}` 为单调递增整数（如 `V1`、`V2`）。
- 版本只增不可回退；**已发布（已执行）的迁移文件严禁修改**，如需变更应新增更高版本的迁移脚本。
- 覆盖 DDL 建表 / 改表、DML 种子数据等所有结构变更。

**设计文档**：[数据库设计文档](docs/database-design.md)（ER 图、字段/索引说明、设计决策、状态枚举）。

## API 文档

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`