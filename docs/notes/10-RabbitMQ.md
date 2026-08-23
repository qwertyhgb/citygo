# 10 RabbitMQ：异步解耦、订单超时与死信队列

> 本篇对应 CityGo 中所有消息中间件的用法：下单后的异步处理、支付成功的多渠道广播、订单超时自动取消的 TTL+死信队列，以及秒杀场景的异步下单（秒杀消费者细节见 11 篇）。学完本篇，你要能回答：什么时候该用 MQ、用哪种交换机、消息丢了/重复了怎么办，以及"订单 30 分钟不支付自动取消"这条最经典的需求怎么落地。

---

## a. 这个模块是干嘛的

CityGo 的下单主链路很重：扣库存、写订单、写明细、清购物车……如果还同步做"库存预警""通知用户/商家""30 分钟后自动取消"这些事，下单接口会又慢又脆。RabbitMQ 在这里承担了三件事：

1. **异步解耦**：下单成功后把"库存预警"这类非核心事扔给消息队列，不阻塞下单响应；
2. **广播**：支付成功后用 Fanout 一次通知多个渠道（用户、商家），未来加渠道不改支付代码；
3. **延迟任务**：利用「消息 TTL + 死信队列」实现"30 分钟未支付自动取消订单"，不依赖定时扫描数据库。

一句话：这一篇讲的是**怎么用 MQ 把"必须马上做"和"可以稍后做"的事情拆开**，让核心链路快而稳。

---

## b. 文件一览

| 文件路径 | 职责 |
|---------|------|
| `mq/config/RabbitConfig.java` | 声明所有交换机/队列/绑定，以及 `JacksonJsonMessageConverter` 消息转换器 |
| `mq/message/OrderMessage.java` | 下单消息体（order.created 与超时延迟消息共用），只放 orderId + orderNo |
| `mq/message/PaymentMessage.java` | 支付消息体，只放 orderId + paymentNo + amount |
| `mq/message/StockWarnMessage.java` | 库存预警载荷（写进 Redis List 的 JSON） |
| `mq/consumer/OrderCreatedConsumer.java` | 下单异步消费者：库存低于阈值 10 时写预警到 Redis List |
| `mq/consumer/PaymentNotifyConsumer.java` | 支付广播消费者：一个类两个 `@RabbitListener` 分别通知用户与商家 |
| `mq/consumer/OrderTimeoutConsumer.java` | 超时消费者：幂等前置校验后调 `cancelBySystem` 自动取消 |
| `order/service/impl/OrderServiceImpl.java` | 下单/支付后 `afterCommit` 发消息；`cancelBySystem`/`cancelInternal` 取消回补 |

---

## c. 先懂概念

- **交换机（Exchange）**：消息先到交换机，再由交换机按规则路由到队列。生产者从不直接发给队列，只发到交换机——这是 RabbitMQ 与简单队列的最大区别。
- **Direct 交换机**：按 `routing key` 精确匹配，一条消息只投递给绑定了相同 key 的队列，适合"点对点"的异步任务。
- **Fanout 交换机**：忽略 routing key，把消息广播给所有绑定的队列，适合"一对多"的通知场景。
- **TTL + 死信队列（DLX）**：消息设过期时间，到期没人消费就变成"死信"，被投递到指定的死信交换机，再由死信交换机路由到处理队列——这是不依赖插件实现"延迟消费"的标准做法。
- **消息可靠性三件套**：生产者确认（publisher confirm）、持久化（队列/消息都 durable）、消费者手动 ACK，三者配合才能"尽量不丢消息"。

---

## d. 逐个详解

### d1. JacksonJsonMessageConverter：SB4 的消息序列化坑

`RabbitConfig` 里声明的转换器是 `JacksonJsonMessageConverter`（注意：**不是** `Jackson2JsonMessageConverter`）。注释里点出了关键：SB4 对应的 Spring AMQP 已经弃用 Jackson 2 系列，必须用 Jackson 3 版的同名类（类名不带 "2"），构造参数传入 Spring Boot 自动配置的 `tools.jackson.databind.json.JsonMapper`。

**为什么消息要 JSON 序列化**：默认的 `SimpleMessageConverter` 用的是 JDK 序列化，消息体是二进制且强绑定类结构，出了 BUG 在 RabbitMQ 控制台根本看不懂。换成 JSON 后消息可读、可跨语言，`@RabbitListener` 方法参数直接写具体类型（如 `OrderMessage`）就能自动反序列化。

**坑在哪**：这是版本升级的典型坑——如果你照着旧教程写 `Jackson2JsonMessageConverter`，在 SB4 里要么找不到类、要么序列化行为异常。声明了 `@Bean` 后，Spring Boot 自动配置会把它应用到 `RabbitTemplate` 和监听器容器，无需手动设置。

### d2. 三种交换机怎么用：Direct / Fanout / TTL+DLX

`RabbitConfig` 里一共声明了四组拓扑，覆盖了三种典型场景：

**① Direct（order.created）——点对点异步**：`citygo.order.created.exchange` 绑定 `citygo.order.created.queue`，routing key = `order.created`。下单后发一条消息，由 `OrderCreatedConsumer` 消费做库存预警。

**② Fanout（payment.success）——一对多广播**：`citygo.payment.success.exchange` 同时绑定用户通知队列和商家通知队列。Fanout 不区分 routing key，绑定的所有队列都收到。注释里强调了核心价值：**后续新增通知渠道只需加一条队列绑定，无需改动支付代码**——这就是广播解耦的意义。

**③ TTL + DLX（订单超时）——延迟消费**：这是本篇的重头戏，链路如下：

```
下单 → order.delay.exchange（Direct）→ order.delay.queue（消息级 TTL，无人消费）
     → 到期变死信 → order.dlx.exchange（死信交换机，routing key = timeout）
     → order.timeout.queue → OrderTimeoutConsumer → 状态机校验 → 取消并回补
```

`orderDelayQueue` 的声明是核心：`QueueBuilder.durable("citygo.order.delay.queue").deadLetterExchange("citygo.order.dlx.exchange").deadLetterRoutingKey("timeout")`——**不设队列级 TTL，用消息级 TTL**，每条消息独立计时。

**④ Direct（seckill.order）——秒杀异步下单**：交换机/队列拓扑与订单一致，消费者是 `SeckillOrderConsumer`，细节见 11 篇。

### d3. 为什么用 TTL+DLX，不用延迟消息插件

`RabbitConfig` 顶部注释给出了两个明确理由：

1. `knowflow-rabbitmq` 是**官方镜像**，不带 `rabbitmq-delayed-message-exchange` 插件；且任务红线**禁止修改容器/安装插件**，只能用 RabbitMQ 原生能力；
2. TTL + DLX 是标准协议能力（`x-message-ttl` / `x-dead-letter-exchange`），零插件即可实现"延迟消费"，面试也能把链路讲清楚。

**消息级 TTL 的坑（队头阻塞）**：注释专门对比了队列级 TTL 的缺陷——队列级 TTL 下，所有消息统一过期时间，如果队首是条"短消息"没到期，后面即使有到期消息也得排队等它，这就是"队头阻塞"。CityGo 用**消息级 TTL**（`setExpiration(String.valueOf(ttlMillis))`），每条消息独立计时，规避了这个问题。面试点：TTL+DLX 的缺点恰恰是"延迟不够精确、有队头阻塞风险"，而延迟插件能精确延迟，但要装插件。

### d4. 下单消息：为什么 afterCommit 才发

`OrderServiceImpl.create` 下单流程的第 l 步调用 `publishOrderCreatedAfterCommit(order)`。这个方法用 `TransactionSynchronizationManager.registerSynchronization` 注册了一个事务同步回调，在 **`afterCommit()`（事务提交成功后）** 才真正调用 `sendOrderMessages`。

**为什么不能事务里直接发**：如果事务回滚，订单实际没落库，此时消息已经发出去了，消费者会处理一个"不存在的订单"（脏数据）。`afterCommit` 保证"订单确实提交成功才通知下游"。

**sendOrderMessages 发两条**：先发 `order.created`（库存预警），再发超时延迟消息——`convertAndSend("citygo.order.delay.exchange", "order.delay", msg, m -> { m.getMessageProperties().setExpiration(...); return m; })`，TTL 毫秒数 = `timeoutMinutes * 60 * 1000`，`timeoutMinutes` 来自 `@Value("${citygo.order.timeout-minutes:30}")`，默认 30 分钟。

**生产怎么升级**：注释里写得很实在——`afterCommit` 是学习项目的够用方案，生产要用**本地消息表 + 定时补偿**的最终一致方案（先写消息表、业务同事务提交、后台任务轮询发送并确认），因为 `afterCommit` 在"发消息时进程挂了"的极端情况下还是会丢消息。

### d5. OrderTimeoutConsumer：超时取消的幂等设计

超时消费者要做两件事：**前置幂等校验** + **条件更新兜底**。

```java
Orders order = ordersMapper.selectById(orderId);
if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT.getCode()) {
    return; // 不存在或已非待支付，直接忽略
}
try {
    orderService.cancelBySystem(orderId);
} catch (BizException e) {
    log.warn("订单超时取消未执行(可能已被处理)...");
}
```

- **前置校验**：订单不存在或状态不是待支付（10），直接忽略——可能已经支付或已被用户取消；
- **条件更新兜底**：`cancelBySystem` 内部走 `cancelInternal`，状态更新用的是 `WHERE status=10` 的条件更新（乐观锁思想），并发下用户恰好同时支付/取消时，条件更新返回 0 行，抛 `BizException` 被捕获转 WARN，**不会重复回补库存**。

**cancelBySystem 与用户取消共用 cancelInternal**：取消的核心逻辑完全一致（状态机校验 → 条件更新 → 回补库存 → 月销回减 → 退券 → 缓存失效 + ES 同步），只是入口不同——系统取消无用户上下文、不做归属校验、原因由系统指定为"超时未支付，系统自动取消"。这是"一个核心、多个入口"的复用设计。

**cancelInternal 里对秒杀订单的特殊处理**：`source=2`（秒杀订单）回补的是 `seckill_stock` 并 Redis `INCR citygo:seckill:stock:{productId}`；普通订单回补普通库存。注意注释强调——秒杀的一人一单 Redis key **不删除**，防止用户取消后再抢，符合"取消后本轮秒杀不可再抢"的业务惯例。

### d6. OrderCreatedConsumer 与 PaymentNotifyConsumer：异步与广播的落地

`OrderCreatedConsumer`：消费 `order.created`，查出订单明细的每个商品，**库存低于阈值 10** 时构造 `StockWarnMessage`，`rightPush` 到 Redis List `citygo:stock.warn`。整个方法体包在 try-catch 里——**单条消息处理异常不能影响同队列其他消息**，这是消费者最基本的原则。

`PaymentNotifyConsumer`：一个类里写了两个 `@RabbitListener` 方法，分别监听用户队列和商家队列。通知内容写入 Redis List `citygo:notify:{userId}` / `citygo:notify:merchant:{merchantId}`，模拟"站内信/通知中心"。注释里点出一个关键区分：**通知类消费者天然幂等**——重复收到同一条支付消息最多是多发一次通知，副作用可接受；而**业务类消费者**（如订单超时取消）必须用条件更新保证幂等。这个区分是面试高频点。

### d7. 消息契约设计：消息体只放稳定字段

`OrderMessage` 只放 `orderId` + `orderNo`，`PaymentMessage` 只放 `orderId` + `paymentNo` + `amount`。注释里说明了设计原则：**消息体是跨系统契约**，一旦发布到 MQ，字段增删会影响所有订阅方，所以只放稳定、核心的字段，需要更多数据时由消费者**反查订单**获得，保证契约轻量稳定。

**坑在哪**：很多初学者把消息体当成"数据搬运工"，把整个订单对象塞进去，结果订阅方一变、字段一改，所有消费者都得跟着改。正确做法是"消息里放定位信息，业务数据反查"。

### d8. SB4 消费者重试配置的两个新坑

`application-dev.yml` 里 RabbitMQ 监听器配置有两个注释点：

1. **`max-retries` 语义变化**：SB4 已把 3.x 的 `max-attempts`（总次数）改成 `max-retries`（仅重试次数）。配置 `retry.max-retries: 2` 表示"重试 2 次，共 3 次尝试"——如果你沿用旧字段名 `max-attempts` 或误以为 2 是"总共 2 次"，行为都会和预期不符。
2. **`default-requeue-rejected: false`**：重试耗尽后，false = 拒绝消息且**不重新入队**（进入 DLX/死信队列），而不是无限重新入队导致消息堆积、死循环消费。这是防止"毒消息"打爆消费者的关键配置。

---

## e. 面试真题

**Q1：RabbitMQ 的交换机有哪几种？Direct 和 Fanout 有什么区别？**
结合 CityGo：交换机是消息路由的中间层，生产者只发到交换机，不直接发队列。Direct 按 routing key 精确匹配（CityGo 的 `order.created`、`seckill.order`），一条消息投给一个队列；Fanout 忽略 routing key、广播给所有绑定队列（CityGo 的 `payment.success` 同时通知用户和商家）。项目里还用到了死信交换机（DLX）实现延迟。
考官想考察什么：是否理解交换机是路由核心，以及 Direct/Fanout/Topic 的路由规则差异和选型。

**Q2：订单 30 分钟不支付自动取消，怎么实现？**
结合 CityGo：用 TTL + 死信队列。下单后 `sendOrderMessages` 发一条带**消息级 TTL**（`setExpiration(timeoutMinutes*60*1000)`）的消息到延迟队列 `order.delay.queue`，该队列声明了 `deadLetterExchange` 和 `deadLetterRoutingKey=timeout`；消息到期无人消费变死信，投到 `order.dlx.exchange`，路由到 `order.timeout.queue`，由 `OrderTimeoutConsumer` 执行取消回补。
考官想考察什么：能否完整画出"TTL 消息 → 延迟队列 → 死信交换机 → 超时消费者"链路，而不是只会说"用定时任务扫表"。

**Q3：为什么用消息级 TTL 而不是队列级 TTL？队列级 TTL 有什么问题？**
结合 CityGo：队列级 TTL 下所有消息统一过期时间，存在**队头阻塞**——队首短消息未到期时，后面即使有到期消息也得排队等。CityGo 的 `orderDelayQueue` 不设队列级 TTL，改用消息级 TTL，每条消息独立计时、独立过期。
考官想考察什么：是否知道"消息级 vs 队列级 TTL"的区别，以及队头阻塞这个 TTL+DLX 方案的经典缺陷。

**Q4：为什么要在事务提交后（afterCommit）才发消息？**
结合 CityGo：如果事务里直接发消息，事务回滚后订单没落库，消费者却收到消息去处理"不存在的订单"。CityGo 的 `publishOrderCreatedAfterCommit` 用 `TransactionSynchronizationManager.registerSynchronization` 注册 `afterCommit` 回调，事务提交成功才 `sendOrderMessages`。生产还会升级为本地消息表 + 定时补偿，因为 afterCommit 在"发消息瞬间进程崩溃"时仍可能丢消息。
考官想考察什么：是否理解 MQ 与数据库事务的一致性问题，以及 afterCommit 的局限与本地消息表方案。

**Q5：消费者怎么保证幂等？重复消费了怎么办？**
结合 CityGo：分两类。通知类（`PaymentNotifyConsumer`）天然幂等，重复消息最多多发一次通知；业务类（`OrderTimeoutConsumer`）必须保证——先查订单，不存在或状态非待支付直接忽略，再调 `cancelBySystem`，内部 `cancelInternal` 用 `WHERE status=10` 条件更新，0 行说明已被处理，抛异常转 WARN，不会重复回补库存。
考官想考察什么：能否区分"天然幂等的场景"和"必须条件更新兜底的场景"，以及条件更新作为幂等武器的原理。

**Q6：如何保证消息不丢失？**
结合 CityGo：三个环节——①生产者端，CityGo 的队列都用 `durable=true`、消息持久化；②Broker 端，持久化队列 + 持久化消息；③消费者端，通过 `default-requeue-rejected: false` 保证处理失败不无限重投。完整的可靠性还需生产者 confirm（publisher confirm）和消费者手动 ACK，CityGo 学习项目用的是自动 ACK + 兜底日志，生产要补上这两环。
考官想考察什么：能否按"生产→Broker→消费"三段讲清丢失点，以及 confirm / 持久化 / 手动 ACK 三件套各自堵住哪一段。

**Q7：Spring Boot 4 / 新版 Spring AMQP 有哪些坑？**
结合 CityGo：两个典型坑——①消息转换器从 `Jackson2JsonMessageConverter` 换成 `JacksonJsonMessageConverter`（类名不带 2，配合 Jackson 3 的 `tools.jackson.JsonMapper`），旧教程的写法在 SB4 里找不到或行为异常；②消费者重试字段从 `max-attempts`（总次数）改为 `max-retries`（仅重试次数），配置 2 表示"重试 2 次共 3 次尝试"。
考官想考察什么：是否关注版本升级的破坏性变更，而不是死记旧版 API。

**Q8：MQ 消息体该怎么设计？**
结合 CityGo：消息体是跨系统契约，`OrderMessage` 只放 `orderId` + `orderNo`、`PaymentMessage` 只放定位字段，需要完整数据时由消费者**反查订单**。如果把整个订单对象塞进消息，字段一改所有订阅方都要跟着改，且消息体臃肿。
考官想考察什么：是否理解"消息只做通知和定位、数据反查"的契约设计原则。

---

## f. 开发实战扩展

- **可靠性补齐**：CityGo 用的是自动 ACK + 日志兜底，生产必须开**生产者 confirm**（发送后异步确认 Broker 收到）+ **消费者手动 ACK**（处理成功才 basicAck，失败 nack 重试），配合本地消息表才能真正做到"不丢、不重"。
- **延迟方案升级**：TTL+DLX 的延迟不够精确，生产对精度要求高的场景（如"精确 15:00 开抢"）会用 **rabbitmq-delayed-message-exchange 插件**或干脆换 **RocketMQ 的定时消息**（支持任意精度的延迟级别），但对"30 分钟超时取消"这种分钟级需求，TTL+DLX 足够且零依赖。
- **削峰填谷**：秒杀场景（见 11 篇）正是用 MQ 削峰——Redis 先预扣拦下大部分流量，只有抢到资格的请求才发 MQ 异步落库，避免瞬时流量直接打爆数据库。
- **死信队列再加工**：生产会把 `default-requeue-rejected: false` 拒绝的消息投进专门的**死信/重试队列**，由运维人工介入或定时任务重放，而不是像学习项目一样只记日志。

---

## g. 文件索引

- MQ 拓扑配置：`src/main/java/com/citygo/mq/config/RabbitConfig.java`
- 消息契约：`src/main/java/com/citygo/mq/message/`（OrderMessage / PaymentMessage / StockWarnMessage）
- 消费者：`src/main/java/com/citygo/mq/consumer/`（OrderCreatedConsumer / OrderTimeoutConsumer / PaymentNotifyConsumer）
- 下单发送与取消：`src/main/java/com/citygo/order/service/impl/OrderServiceImpl.java`（`publishOrderCreatedAfterCommit` / `sendOrderMessages` / `cancelBySystem` / `cancelInternal`）
- 配置：`src/main/resources/application-dev.yml`（rabbitmq listener 重试配置、`citygo.order.timeout-minutes: 30`）
- 秒杀消费者：`src/main/java/com/citygo/seckill/consumer/SeckillOrderConsumer.java`（详见 11 篇）
