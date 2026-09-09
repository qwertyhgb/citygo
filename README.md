# CityGo · 本地生活服务平台后端

> 一个面向真实互联网业务场景设计的**本地生活服务平台后端**，参考美团核心业务，覆盖「用户 → 商家 → 店铺 → 商品 → 购物车 → 订单 → 支付 → 营销（优惠券）→ 评价 → 搜索 → 秒杀」的完整链路。
>
> 🎓 **定位：学习项目（非生产）**。项目以单体架构起步，按业务包（auth / user / merchant / shop / product / order / coupon / review / search / seckill …）清晰划分边界，是系统学习 Spring Boot 4 + 微服务常用中间件（Redis / RabbitMQ / Elasticsearch）的实战载体。后续可按业务边界平滑演进为微服务。

---

## 📌 功能模块与实现状态

| 模块 | 已实现能力 | 关键端点 |
| --- | --- | --- |
| 🔐 认证授权 | 注册 / 登录 / 登出、JWT + Redis 登录态、BCrypt 密码、RBAC 角色（USER / MERCHANT / ADMIN） | `/api/auth/*` |
| 👤 用户 | 当前用户信息、账号启用/禁用 | `/api/users/me` |
| 🏪 商家 / 店铺 | 商家入驻、店铺 CRUD、营业状态切换、我的店铺、店铺列表与详情、店铺商品列表 | `/api/merchants`, `/api/shops` |
| 📦 商品 / 分类 | 商品 CRUD、上架/下架、库存调整、分类层级、商品列表/详情/热门、商家端秒杀配置 | `/api/products`, `/api/categories` |
| 🛒 购物车 | 加入 / 修改数量 / 删除 / 清空 / 查询 | `/api/cart` |
| 📍 收货地址 | 增删改查、默认地址 | `/api/addresses` |
| 🧾 订单 / 支付 | 下单（快照 + 库存扣减）、我的订单、订单详情、取消、支付；商家端接单/发货/完成；支付记录；状态机流转；普通/秒杀来源 | `/api/orders` |
| 🎟️ 优惠券 | 券模板创建、可领券列表、领取、我的券、满减/折扣、平台券/商家券、使用门槛 | `/api/coupons`, `/api/coupons/{id}/claim`, `/api/coupons/my` |
| ⭐ 评价 | 一单一评、商家回复、我的评价、店铺评价列表、评分联动店铺 `score`/`review_count` | `/api/reviews`, `/api/shops/{id}/reviews` |
| 🔎 搜索 | 基于 Elasticsearch 的店铺 / 商品全文检索（IK 分词 + geo_point） | `/api/search/shops`, `/api/search/products` |
| ⚡ 秒杀 | Redis 预扣库存 + Lua 原子「一人一单」+ MQ 异步落库 + DB 条件更新兜底 | `/api/seckill/{productId}`, `/api/products/{id}/seckill` |
| 📨 消息队列 | 下单库存预警、支付成功广播（用户+商家）、订单超时（TTL+死信队列）、秒杀异步下单 | RabbitMQ（`citygo.*` 交换机/队列） |
| 🧰 公共能力 | 统一返回 `Result`、全局异常处理、分布式锁、接口限流、防重复提交（幂等） | `common.*` |

---

## 🧱 技术栈版本矩阵

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java (JDK) | 21.0.8 | 基线语言版本 |
| Spring Boot | 4.1.1 | 主框架（注：SB4 已将 `web`→`webmvc`、`aop`→`aspectj` 重命名） |
| MyBatis-Plus | 3.5.17 | ORM（含 `mybatis-plus-jsqlparser` 分页支持模块） |
| Springdoc (OpenAPI) | 3.1.0 | 接口文档（Swagger UI） |
| Spring Security | 4.1.1（BOM） | 认证/授权（JWT + Redis 无状态） |
| jjwt | 0.13.0 | JWT 生成/解析（api/impl/jackson 三件套） |
| MySQL | 8.4.11 | 主存储（utf8mb4 / InnoDB） |
| Redis | 7 | 登录态、缓存、分布式锁、限流、幂等、秒杀预扣 |
| RabbitMQ | 3 | 异步消息、支付广播、订单超时（TTL+死信） |
| Elasticsearch | 9.4.2 | 商品/店铺全文搜索（IK 分词，Spring Data ES 6.1.0） |
| Flyway | 10+（BOM） | 数据库版本迁移（含 `flyway-mysql` 支持包） |
| Maven | 3.9.12 | 构建 |
| Node | 24.11.1 | 前端/工具（预留） |
| Docker | 29.4.0 | 本地中间件容器化部署 |

> 依赖版本管理：Spring Boot BOM 锁定的依赖（Flyway / Security / Redis / AMQP / ES 等）在 `pom.xml` 中**不写 `<version>`**；仅 BOM 未覆盖的（MyBatis-Plus、springdoc、jjwt）手写版本号。

---

## 🏗️ 整体架构

单体 Spring Boot 应用，按业务领域分包，典型请求链路：

```
Client
  │  (Bearer JWT)
  ▼
Spring Security 过滤链
  ├─ JwtAuthenticationFilter  ── 校验 JWT + Redis 登录态 ──▶ SecurityContext(userId, roles)
  ▼
@Controller / @RestController
  ▼
@Service  ──(事务)──▶ MyBatis-Plus Mapper ──▶ MySQL
  │
  ├─ Redis（登录态 / 缓存 / 锁 / 限流 / 幂等 / 秒杀预扣）
  ├─ RabbitTemplate  ──▶ RabbitMQ（异步解耦、削峰、延迟）
  └─ ElasticsearchOperations  ──▶ ES（搜索索引，afterCommit 双写同步）
```

**基础设施依赖**：开发/测试环境连接本机 Docker 容器中的 MySQL、Redis、RabbitMQ（官方镜像，无延迟插件）、Elasticsearch（带 IK 分词）。所有连接信息均可通过环境变量覆盖，生产配置仅保留占位骨架。

---

## 📂 项目结构

```
citygo-server/
├── pom.xml                      # 依赖与构建（SB4 parent + 各中间件 starter）
├── sql/
│   └── init_citygo_db.sql       # 建库 + 专用账号（citygo / citygo_user）
├── docs/
│   ├── database-design.md       # 数据库设计文档（ER 图、字段/索引、设计决策、枚举）
│   └── notes/                   # 分模块学习笔记 01~15（含架构讲解与面试点）
├── src/main/java/com/citygo/
│   ├── CityGoApplication.java   # 启动入口（@MapperScan("com.citygo.**.mapper")）
│   ├── common/                  # 统一返回/异常/错误码/分布式锁/限流/幂等切面
│   ├── config/                  # MyBatis-Plus、Redis 缓存、OpenAPI 配置
│   ├── auth/                    # 认证、JWT、Security 配置、角色/权限
│   ├── user/                    # 用户
│   ├── merchant/                # 商家、店铺（管理端）
│   ├── shop/                    # 店铺（浏览端）
│   ├── product/                 # 商品、分类（含秒杀配置）
│   ├── cart/  address/          # 购物车、收货地址
│   ├── order/                   # 订单、支付、订单状态机、商家端订单
│   ├── coupon/                  # 优惠券、用户券
│   ├── review/                  # 评价、店铺评价
│   ├── search/                  # ES 索引/仓储/服务/同步
│   └── seckill/  mq/            # 秒杀、RabbitMQ 配置与消费者
├── src/main/resources/
│   ├── application.yml          # 通用配置（端口/Flyway/springdoc/actuator）
│   ├── application-dev.yml      # 开发环境（连接本地容器）
│   ├── application-test.yml     # 测试环境（共用本地容器，压低日志）
│   ├── application-prod.yml     # 生产骨架（敏感信息全走环境变量）
│   ├── db/migration/            # Flyway 迁移脚本 V1~V6
│   └── logback-spring.xml       # 日志（控制台 + 按天滚动 + 分环境）
└── src/test/java/com/citygo/    # 分层测试：单元 + MockMvc + 集成（MQ/Redis/ES/Cache）
```

---

## 🔌 API 一览

> 鉴权说明：**公开** = 无需登录；**需登录** = 请求头携带 `Authorization: Bearer <token>`。
> 统一返回结构见下文「核心设计」。完整交互式文档见 Swagger（启动后访问 `/swagger-ui.html`）。

### 认证 · `/api/auth`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/register` | 公开 | 用户注册（BCrypt 加密，默认绑定 USER 角色） |
| POST | `/login` | 公开 | 登录，签发 JWT 并写入 Redis 登录态，返回 token + 用户信息 |
| POST | `/logout` | 需登录 | 删除 Redis 登录态（幂等） |

### 用户 · `/api/users`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/me` | 需登录 | 当前登录用户信息 |

### 商家 / 店铺 · `/api/merchants`、`/api/shops`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/merchants/register` | 公开 | 商家入驻（创建用户+商家身份） |
| POST | `/api/shops` | 需登录 | 当前商家创建店铺 |
| PUT | `/api/shops/{id}` | 需登录 | 修改店铺信息（越权校验） |
| PATCH | `/api/shops/{id}/open-status` | 需登录 | 营业/打烊切换 |
| GET | `/api/shops/my` | 需登录 | 当前商家的店铺列表 |
| GET | `/api/shops` | 公开 | 店铺列表（按城市/区域/评分筛选） |
| GET | `/api/shops/{id}` | 公开 | 店铺详情 |
| GET | `/api/shops/{id}/products` | 公开 | 店铺下商品列表 |

### 商品 / 分类 · `/api/products`、`/api/categories`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/products` | 需登录 | 商家创建商品 |
| PUT | `/api/products/{id}` | 需登录 | 修改商品 |
| PATCH | `/api/products/{id}/status` | 需登录 | 上架/下架 |
| PATCH | `/api/products/{id}/stock` | 需登录 | 调整库存 |
| POST | `/api/products/{id}/seckill` | 需登录 | 配置秒杀（价/库存/时段，预热 Redis） |
| GET | `/api/products/my` | 需登录 | 当前商家的商品 |
| GET | `/api/products` | 公开 | 商品列表（分类/价格/关键词，分页） |
| GET | `/api/products/hot` | 公开 | 热门商品 |
| GET | `/api/products/{id}` | 公开 | 商品详情（含缓存） |
| GET | `/api/categories` | 公开 | 分类树/列表 |

### 购物车 · `/api/cart` / 地址 · `/api/addresses`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/cart/items` | 需登录 | 加入购物车 |
| PUT | `/api/cart/items/{productId}` | 需登录 | 修改数量 |
| DELETE | `/api/cart/items/{productId}` | 需登录 | 删除项 |
| DELETE | `/api/cart` | 需登录 | 清空 |
| GET | `/api/cart` | 需登录 | 购物车列表 |
| POST | `/api/addresses` | 需登录 | 新增地址 |
| GET | `/api/addresses` | 需登录 | 地址列表 |
| PUT | `/api/addresses/{id}` | 需登录 | 修改地址 |
| DELETE | `/api/addresses/{id}` | 需登录 | 删除地址 |

### 订单 / 支付 · `/api/orders`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/orders` | 需登录 | 下单（库存 CAS 扣减 + 收货快照 + 发延迟消息） |
| GET | `/api/orders/{id}` | 需登录 | 订单详情 |
| GET | `/api/orders/my` | 需登录 | 我的订单（按状态） |
| POST | `/api/orders/{id}/cancel` | 需登录 | 取消订单（回补库存） |
| POST | `/api/orders/{id}/pay` | 需登录 | 支付（afterCommit 广播支付成功） |
| GET | `/api/orders/merchant` | 需登录 | 商家端订单列表 |
| POST | `/api/orders/{id}/accept` | 需登录 | 商家接单 |
| POST | `/api/orders/{id}/deliver` | 需登录 | 商家发货 |
| POST | `/api/orders/{id}/complete` | 需登录 | 完成订单 |

### 优惠券 · `/api/coupons`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/coupons` | 需登录 | 创建券模板（满减/折扣、平台/商家券） |
| GET | `/api/coupons` | 公开 | 可领券列表 |
| POST | `/api/coupons/{id}/claim` | 需登录 | 领取（幂等，每人限领） |
| GET | `/api/coupons/my` | 需登录 | 我的券（未用/已用/已过期） |

### 评价 · `/api/reviews`、`/api/shops/{id}/reviews`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/reviews` | 需登录 | 提交评价（一单一评，联动店铺评分） |
| POST | `/api/reviews/{id}/reply` | 需登录 | 商家回复 |
| GET | `/api/reviews/my` | 需登录 | 我的评价 |
| GET | `/api/shops/{id}/reviews` | 公开 | 店铺评价列表 |

### 搜索 · `/api/search`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/search/shops` | 公开 | 店铺全文检索 |
| GET | `/api/search/products` | 公开 | 商品全文检索 |

### 秒杀 · `/api/seckill`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/seckill/{productId}` | 需登录 | 秒杀抢购（Redis 预扣 + Lua 原子 + MQ 异步下单） |

### 连通性 · `/api/ping`
| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/ping` | 公开 | 健康检查/连通性探测 |

---

## 🗄️ 数据库

CityGo 使用 **Flyway** 管理数据库结构迁移：应用启动时自动按版本号顺序执行 `src/main/resources/db/migration/` 下的脚本，并把执行记录写入 `flyway_schema_history` 表，保证 dev / test / prod 三套环境库结构一致、可追溯。

**当前共 14 张业务表**（覆盖用户/权限、商家/店铺、商品/分类、订单/支付、营销/评价/地址）：`user`、`role`、`user_role`、`merchant`、`shop`、`category`、`product`、`orders`、`order_item`、`payment`、`coupon`、`user_coupon`、`review`、`address`。秒杀能力以 `product` 扩展字段（`seckill_price/stock/start/end`）与 `orders.source`（1 普通 / 2 秒杀）实现，未新增独立表。

**迁移脚本（V1~V6）**：
- `V1__init_schema.sql`：14 张业务表建表 + 索引
- `V2__init_data.sql`：角色种子数据（USER / MERCHANT / ADMIN）
- `V3__init_categories.sql`：初始分类数据
- `V4__init_coupons.sql`：示例券模板
- `V5__seckill_fields.sql`：秒杀字段扩展
- `V6__fix_user_coupon_and_shop_review_count.sql`：修正用户券唯一键 + 新增 `shop.review_count` 冗余字段

**设计文档**：[`docs/database-design.md`](docs/database-design.md)（ER 图、字段/索引说明、8 条设计决策、状态枚举汇总）。

> 关键设计取舍：不使用物理外键（逻辑外键 + 索引）、主键用雪花 ID、全局逻辑删除（`deleted`）、订单/收货信息快照、金额用 `DECIMAL`、状态枚举离散取值（10→80）、销量/评分冗余字段。

---

## 🔐 核心工程设计与亮点

### 1. 统一返回 & 全局异常
- `Result<T>`：固定 `{code, message, data}`，**HTTP 状态码恒为 200**，业务成败由 `code` 承载（`200` 成功，其余见 `ErrorCode`）。
- `GlobalExceptionHandler`（`@RestControllerAdvice`）：`BizException`、参数校验、`AuthorizationDeniedException`、404 等统一收敛为 `Result`；业务异常记 WARN、系统异常记 ERROR 并打印堆栈，**绝不向调用方暴露内部细节**。

### 2. 认证授权（JWT + Redis 无状态）
- 登录用 `BCryptPasswordEncoder` 校验密码（不走 `DaoAuthenticationProvider`），成功后签发 JWT（`jjwt 0.13` 新 API），并把 `citygo:login:token:{token} → userId:roleCodes` 写入 Redis（TTL = token 有效期）。
- `JwtAuthenticationFilter` 在 `UsernamePasswordAuthenticationFilter` 之前执行：解析 JWT + **校验 Redis 登录态**，二者均通过才写入 `SecurityContext`（principal 为 `userId`）。登出即删除该 key，**即时失效**。
- RBAC：`user_role` 多对多关联 `role`，`SecurityConfig` 按路径 + 方法精确配置放行/鉴权，未认证返回 401、无权限返回 403（框架层直接拼装 JSON，不依赖 MVC 异常处理器）。
- 安全细节：登录失败统一返回「用户名或密码错误」防撞库；公开读接口（店铺/商品/分类/可领券/搜索/ping）放行，写接口与「我的」类接口强制登录。

### 3. Redis 多场景应用
- **登录态**（见上）、**Spring Cache 缓存**（JSON 序列化、`缓存名::key` 前缀、默认 TTL 10 分钟，如店铺详情/热门商品）、**分布式锁**（`SET key uuid EX NX` + Lua 原子解锁，防误删他人锁）、**接口限流**（固定窗口计数器，Lua 原子 `INCR+EXPIRE`，超限抛 429）、**防重复提交**（基于 `X-Request-Id` 的 `SETNX` 幂等，超时抛 409）。

### 4. 消息队列（RabbitMQ）
四类消息流：
- **下单 → 库存预警**：Direct 交换机 `order.created` → 库存预警消费者（库存 < 阈值写入 `citygo:stock.warn`）。
- **支付成功 → 广播通知**：Fanout 交换机 `payment.success` → 用户队列 + 商家队列（一次支付多渠道通知，**新增渠道零改支付代码**）。
- **订单超时**：下单发 **消息级 TTL** 延迟消息 → 到期转死信交换机 `dlx` → 超时消费者校验状态机（10→60）取消并回补库存（规避队列级 TTL 队头阻塞）。
- **秒杀异步下单**：Direct 交换机 `seckill.order` → 秒杀消费者落库。
- 消息统一 JSON 序列化（`JacksonJsonMessageConverter`，Jackson 3）；消费者重试 2 次后入死信，不无限回队。

### 5. 秒杀（高并发）
`SeckillServiceImpl` 设计：**① Redis `SETNX` 一人一单查重（TTL 覆盖活动期）；② Redis `DECR` 内存预扣库存防超卖；③ Lua 脚本原子执行「查重 + 扣减 + 占位」消除并发间隙；④ 预扣成功后投递 MQ 异步削峰落库；⑤ 消费者端再次 DB 一人一单复查 + 条件更新扣减，极端场景回补 Redis**。热点配置（秒杀价/时段）预热进 Redis，请求零查库。

### 6. 搜索（Elasticsearch）
- 索引 `citygo_shop` / `citygo_product`，启动时 `ESIndexInitializer` 自动建索引 + 写映射（IK 分析器 + `geo_point`）。
- **双写一致性**：MySQL 提交成功后（`TransactionSynchronizationManager.afterCommit`）再同步 ES，ES 失败仅记 ERROR 不阻断主流程（主库为准，允许短暂不一致）。

### 7. 订单状态机 & 快照
- 状态离散取值：`10 待支付 → 20 已支付 → 30 商家已接单 → 40 配送中 → 50 已完成`，并含 `60 已取消 / 70 退款中 / 80 已退款`；状态流转用 **条件更新**（`UPDATE ... WHERE status = ?`）保证状态安全。
- 下单时固化商品名/单价、收货人信息**快照**，历史订单永远可正确展示。
- 库存扣减用 **CAS 条件更新**（`UPDATE ... WHERE stock >= ?`）防超卖，取消/超时回补。

### 8. MyBatis-Plus
- 雪花 ID 主键、全局逻辑删除（`deleted`）、`PaginationInnerInterceptor` 物理分页、下划线↔驼峰自动映射。

---

## 🚀 快速开始

### 前置依赖
- JDK 21、Maven 3.9+
- 本地中间件（推荐 Docker）：MySQL 8.4、Redis 7、RabbitMQ 3（官方镜像）、Elasticsearch 9（带 IK 分词插件）

### 1. 初始化数据库与账号
```bash
# 连接你的 MySQL 执行（仅建库 + 专用账号，不触碰现有数据）
mysql -u root -p < sql/init_citygo_db.sql
```
Flyway 会在应用首次启动时自动建表/迁移（无需手动执行 `db/migration` 脚本）。

### 2. 配置连接（环境变量，可选）
默认连接 `localhost` 上的 Docker 容器，密码回退到 `Citygo@2026`。覆盖方式：
```bash
export MYSQL_PASSWORD='你的密码'
export MYSQL_HOST=127.0.0.1
# prod 还需：MYSQL_PORT / MYSQL_DATABASE / MYSQL_USERNAME / SERVER_PORT
# JWT 生产必须改：citygo.jwt.secret（≥32 字节）
```

### 3. 构建 & 启动
```bash
# 运行测试（需本地 MySQL/Redis/RabbitMQ/ES 均可用）
mvn clean test

# 启动应用（默认 dev 环境，端口 8080）
mvn spring-boot:run
```

### 4. 访问入口
- 接口调试：**http://localhost:8080/swagger-ui.html**
- OpenAPI JSON：http://localhost:8080/v3/api-docs
- 健康检查：http://localhost:8080/actuator/health
- 连通性：http://localhost:8080/api/ping

---

## ⚙️ 配置说明（多环境）

| Profile | 用途 | 数据源 | 说明 |
| --- | --- | --- | --- |
| `dev`（默认） | 本地开发 | 本机容器 | 开启 SQL 日志、debug 日志、Flyway 自动迁移 |
| `test` | 测试 | 本机容器 | 与 dev 共用库，压低日志 |
| `prod` | 生产骨架 | 环境变量注入 | 敏感信息全走 `${ENV}`，不硬编码 |

切换环境：`mvn spring-boot:run -Dspring-boot.run.profiles=test` 或启动时 `--spring.profiles.active=test`。

---

## 🧪 测试

- **单元测试 / MockMvc**：各模块 Controller、Service 测试，验证业务逻辑与参数校验。
- **集成测试**：`MqOrderFlowTest`（真实 RabbitMQ + Redis 链路：库存预警、支付广播、超时关单回补）、`SearchIntegrationTest`、`CacheIntegrationTest`、`IdempotentTest` 等。
- 集成测试连接**真实中间件**（localhost 容器），测试数据用唯一后缀隔离；消息在事务 `afterCommit` 发送，故集成测试**不标注 `@Transactional`** 以触发真实消息流。

```bash
mvn test                 # 全部测试
mvn -Dtest=SeckillControllerTest test   # 单个测试类
```

---

## 📖 API 文档

基于 springdoc（OpenAPI 3）自动生成：
- Swagger UI：**`/swagger-ui.html`**
- 规范 JSON：**`/v3/api-docs`**

---

## 📐 开发规范

**Flyway 迁移脚本规则**（重要）：
- 命名：`src/main/resources/db/migration/V{版本}__{描述}.sql`，`{版本}` 单调递增整数。
- 版本只增不可回退；**已发布（已执行）的迁移文件严禁修改**，变更须新增更高版本脚本。
- 覆盖 DDL（建/改表）与 DML（种子数据）等所有结构变更。

**代码分层约定**：
- 每业务包遵循 `controller → service → mapper → entity` + `dto`（入参）/ `vo`（出参）分层；
- 跨层异常用 `BizException(ErrorCode)` 表达，由全局处理器统一收敛；
- 所有对外接口返回 `Result<T>`，HTTP 状态码保持 200。

---

## 🗺️ 路线图

- [x] 工程地基（统一返回 / 异常 / 日志 / 文档 / Flyway）
- [x] 认证授权与 RBAC
- [x] 用户 / 商家 / 店铺 / 商品 / 分类
- [x] 购物车 / 收货地址
- [x] 订单 / 支付 + 状态机 + 超时关单
- [x] 优惠券 / 用户券
- [x] 评价与评分联动
- [x] Elasticsearch 搜索 + 双写同步
- [x] RabbitMQ 异步消息（预警 / 广播 / 超时 / 秒杀）
- [x] 秒杀（Redis 预扣 + Lua + MQ 削峰）
- [ ] 微服务拆分（按业务边界独立部署）
- [ ] 支付对接真实三方（微信 / 支付宝）
- [ ] 限流/熔断治理（网关层）、监控与链路追踪

---

> 📚 配套学习笔记见 [`docs/notes/`](docs/notes/)（共 15 篇，含架构讲解、关键代码走读与面试点）。
