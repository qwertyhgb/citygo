# 15 MyBatis-Plus 实战要点：逻辑删除、雪花 ID、条件更新与测试陷阱

> 本篇是 CityGo 的收尾篇，把贯穿全项目的 MyBatis-Plus 实战经验集中梳理一遍：全局逻辑删除、雪花 ID、Lambda 条件构造器、条件更新 SQL、分页、批量查询，以及一个踩坑很深的测试残留教训。学完本篇，你要能回答：MyBatis-Plus 用好了能帮你解决什么、用错了会踩哪些坑，以及"为什么有些 SQL 必须自己写"。

---

## a. 这个模块是干嘛的

CityGo 的持久层没有手写一行 XML，全靠 MyBatis-Plus（3.5.17）的 `BaseMapper` 通用 CRUD + Lambda 条件构造器 + 少量自定义 `@Update/@Select` 注解 SQL。MyBatis-Plus 帮我们干掉了 90% 的样板代码（增删改查、分页、逻辑删除、主键生成），但**关键的 10%——防超卖、防超发、评分聚合——必须自己写 SQL**，因为通用方法在并发安全上不够用。这一篇就是把这两类经验都讲清楚。

一句话：这一篇讲的是**怎么把 MyBatis-Plus 当工具用好，同时清楚它的边界——什么时候该信任它，什么时候必须亲自写 SQL**。

---

## b. 文件一览

| 文件路径 | 职责 |
|---------|------|
| `config/MybatisPlusConfig.java` | 注册分页插件 `PaginationInnerInterceptor(DbType.MYSQL)` |
| `CityGoApplication.java` | `@MapperScan("com.citygo.**.mapper")` 统一扫描 Mapper |
| `user/entity/User.java` 等各实体 | `@TableName`、`@TableId(type=IdType.ASSIGN_ID)`、逻辑删除字段 |
| `product/mapper/ProductMapper.java` | 自定义 `@Update` SQL：`deductStock`/`deductSeckillStock`/`restoreStock`/`addSales` |
| `review/mapper/ReviewMapper.java` | 自定义 `@Select` SQL：`avgRatingByShop`（AVG 聚合） |
| 各 Service 实现 | `Wrappers.lambdaQuery/lambdaUpdate`、`selectByIds`、`selectPage` 的实战用法 |
| `src/test/java/com/citygo/seckill/SeckillControllerTest.java` | 测试事务与异步消费者事务边界的残留教训 |

---

## c. 先懂概念

- **逻辑删除**：不真的 `DELETE` 数据，而是 `UPDATE deleted=1` 标记删除。好处是数据可追溯、误删可恢复；坏处是查询都要带 `deleted=0` 条件、唯一索引与逻辑删除会冲突。
- **雪花 ID（Snowflake）**：分布式唯一 ID 算法，由时间戳 + 机器号 + 序列号组成，全局唯一、趋势递增，不依赖数据库自增。`ASSIGN_ID` 就是 MP 对它的封装。
- **条件更新（乐观锁思想）**：`UPDATE ... WHERE status=1` 这类带状态条件的更新，靠数据库行锁保证并发下只有一次成功，返回 0 行即"已被抢先"。这是防超卖、幂等核销的通用武器。
- **Lambda 条件构造器**：`Wrappers.lambdaQuery().eq(User::getId, 1)` 用方法引用代替手写列名字符串，编译器帮你校验字段名，改名/重构时不会漏改。
- **searchCount**：MP 分页默认会先 `SELECT count(*)` 再查数据，共两条 SQL。当只需要"前 N 条"而不关心总数时，可以关掉 count 查询省一条 SQL。

---

## d. 逐个详解

### d1. 逻辑删除：全局配置 vs @TableLogic

CityGo 的逻辑删除用的是**全局配置**（`application-dev.yml`）：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted    # 全局逻辑删除字段名
      logic-delete-value: 1          # 已删除
      logic-not-delete-value: 0      # 未删除
```

**为什么用全局配置而不是 `@TableLogic` 注解**：全局配置一次声明 `deleted` 字段，所有实体的 `deleted` 属性自动获得逻辑删除能力，不用每个实体逐个加注解。`User` 实体注释里写得很明确："逻辑删除字段 deleted 由全局配置自动生效，本实体仅需保留该属性，无需额外 @TableLogic 注解"。

**生效原理**：MP 内置的 `selectById`、`selectList`、`updateById`、`deleteById` 等方法会自动拼 `deleted=0` 条件，`deleteById` 会变成 `UPDATE ... SET deleted=1`。

**坑在哪**：逻辑删除的"自动拼条件"**只对内置方法生效**。自己写的 `@Select`/`@Update` 自定义 SQL 不走这个增强，必须手动加 `deleted=0`——13 篇的 `avgRatingByShop` 就是活生生的例子（SQL 里手写了 `AND deleted = 0`）。漏了这条，已删除数据会混进查询结果。

### d2. 雪花 ID：为什么用 ASSIGN_ID 不用自增

所有实体的主键都标注 `@TableId(type = IdType.ASSIGN_ID)`。注释里的理由：

- **分布式友好**：自增 ID 依赖数据库的 `AUTO_INCREMENT`，单体没问题，但一旦分库分表、多实例写入，自增会冲突。雪花 ID 由算法在应用侧生成，全局唯一，不依赖数据库；
- **趋势递增**：雪花 ID 大致按时间递增，对 B+ 树索引友好，避免随机 UUID 的页分裂。

**坑在哪**：雪花 ID 是 64 位 long，前端 JS 的 `Number` 类型只能精确表示 53 位整数，超过会丢精度。CityGo 对外展示的 `orderNo` 用的是 `IdWorker.getIdStr()`（字符串），避开了这个问题——订单主键是雪花 long，对外业务单号是字符串。

### d3. 自定义 @Update SQL：为什么防超卖必须自己写

这是 MyBatis-Plus 实战最关键的一条边界。`ProductMapper.deductStock`：

```java
@Update("UPDATE product SET stock = stock - #{quantity}, sales = sales + #{quantity} " +
        "WHERE id = #{id} AND stock >= #{quantity}")
int deductStock(Long id, int quantity);
```

**为什么不用 `updateById`**：`updateById` 是"读实体再全量更新"，逻辑是"先查出库存、判断够不够、再写回"。高并发下两个请求同时读到同一库存，都判定充足，最终扣成负数（超卖）。而 `UPDATE ... WHERE stock >= ?` 是**单条原子语句**，数据库行锁保证同一时刻只有一个请求扣减成功，返回 0 影响行数即库存不足。

同理，`deductSeckillStock`（秒杀兜底）、领券的 `received_count < total_count` 条件更新（12 篇）、核销的 `status=1` 条件更新，都是这个思想。**通用 CRUD 解决"怎么改"，条件更新 SQL 解决"并发下怎么安全地改"**，后者必须自己写。

### d4. 条件更新的两种写法：setSql / apply 与 @Update

CityGo 里条件更新有两条路：

1. **`@Update` 注解自定义 SQL**：如 `deductStock`，适合复杂、频繁复用、需要精确控制的更新；
2. **`LambdaUpdateWrapper` + `setSql`/`apply`**：如领券超发控制——

```java
couponMapper.update(null, Wrappers.<Coupon>lambdaUpdate()
        .eq(Coupon::getId, couponId)
        .apply("received_count < total_count")   // 原生 WHERE 条件
        .setSql("received_count = received_count + 1")); // 原生 SET 片段
```

**为什么这里用 Wrapper 而不是 @Update**：领券的更新只有一个条件 + 一个自增片段，用 Wrapper 更直观、与业务代码就近；`deductStock` 这种被多处调用、语义独立的更新，抽到 Mapper 接口更清晰。两条路都能实现"条件更新"，选哪条看复用度和复杂度。

**坑在哪**：`setSql` 和 `apply` 都是**拼接原生 SQL 片段**，参数如果来自用户输入会有注入风险。CityGo 里这两处的条件（`received_count < total_count`、`received_count = received_count + 1`）都是硬编码常量，没有用户输入，所以安全。如果业务里要用用户输入拼 `apply`，必须自己校验或参数化。

### d5. 分页插件与 searchCount

`MybatisPlusConfig` 注册分页插件：

```java
interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
```

**坑在哪**：注释特别强调 `DbType` 用 3.5.9+ 的**枚举写法**（`DbType.MYSQL`），勿用旧版字符串常量。这是版本升级的破坏性变更——旧教程写 `new PaginationInnerInterceptor(DbType.MYSQL)` 没问题，但更老的版本有 `new PaginationInnerInterceptor("mysql")` 的字符串构造，新版已废弃。

分页用法 `new Page<>(pageNum, pageSize)`，MP 默认执行两条 SQL：先 `count` 再查数据。`queryHotFromDb` 里的 `new Page<>(1, Math.max(limit, 1), false)`——**第三个参数 `false` 就是 `searchCount=false`**，跳过 count 查询。热门商品只取前 N 条、不需要总数，关掉 count 省一条 SQL，性能敏感场景值得注意。

### d6. selectByIds：批量查询防 N+1

全项目反复出现的模式：先 `distinct()` 收集 id 列表，再 `selectByIds(ids)` 批量查，`toMap` 组装。典型如 `ProductServiceImpl.loadCategoryNames`（商品列表补分类名）、购物车补商品信息、领券补券模板、评价补店铺名/用户昵称。

**为什么不用逐条 `selectById`**：分页 20 条记录，每条查一次就是 20 次 SQL（N+1）；`selectByIds` 一次 `WHERE id IN (...)` 全拉回，20 条记录只需 1 次查询。这是"批量 in 查询 + Map 组装"的标准优化，贯穿全项目。

### d7. 测试事务与异步消费者事务的边界（残留教训）

这是 CityGo 踩坑最深的地方，记录在 `SeckillControllerTest` 的注释里：

**问题**：秒杀订单是由 MQ 消费者在**独立线程、独立事务**中异步创建的，测试方法上的事务回滚机制**覆盖不到**消费者已提交的异步订单。也就是说，测试主线程回滚了，但消费者已经把秒杀订单提交到数据库，残留脏数据。

**两重防护**：

1. 所有 `waitUntil` 轮询查询**严格按当前测试用户的 userId 过滤**，避免命中历史残留订单；
2. 每次测试前后用 `@BeforeEach`/`@AfterEach` 清理秒杀订单、明细和 Redis 键。

**为什么必须物理硬删**：消费者异步插入的订单不在测试主线程事务内，MP 的 `deleteById` 会执行**软删除**（`deleted=1`），残留的 `deleted=1` 记录仍会污染后续测试（比如唯一索引冲突），所以必须用 `jdbcTemplate.update("DELETE FROM ...")` 做**物理硬删除**：

```java
jdbcTemplate.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE source = 2)");
jdbcTemplate.update("DELETE FROM orders WHERE source = 2");
```

**这给我们的启示**（面试/实战通用）：当业务里存在"异步线程 + 独立事务"的写入（MQ 消费者、定时任务、异步任务），主线程的事务回滚**管不到它们**。测试清理和幂等设计都要额外考虑这条边界——软删除不是万能药，物理删除和按条件过滤才是清理残留的正确姿势。

### d8. MyBatis-Plus 与 ES Repository 的分工

全项目的数据访问分两套：MyBatis-Plus（MySQL）管**主数据与事务**，Spring Data ES Repository 管**搜索索引**。注释里反复强调"MySQL 是唯一事实源，ES 是搜索索引"——写操作永远先落 MySQL，事务提交后再 `afterCommit` 同步 ES；读操作（搜索）才走 ES。两者不互相替代：MP 负责 ACID，ES 负责全文检索和地理查询。

---

## e. 面试真题

**Q1：MyBatis-Plus 的逻辑删除怎么配？全局配置和 @TableLogic 注解有什么区别？**
结合 CityGo：用全局配置——`logic-delete-field: deleted`、`logic-delete-value: 1`、`logic-not-delete-value: 0`，所有实体的 `deleted` 属性自动获得逻辑删除能力，不用逐个加 `@TableLogic`。内置方法自动拼 `deleted=0`，`deleteById` 变 `UPDATE SET deleted=1`。但注意：自定义 `@Select/@Update` 不走这个增强，要手动加 `deleted=0`（如 `avgRatingByShop`）。
考官想考察什么：是否理解"全局配置 vs 注解"的选择，以及逻辑删除增强的生效边界（自定义 SQL 要自己兜底）。

**Q2：为什么主键用雪花 ID（ASSIGN_ID）而不是数据库自增？**
结合 CityGo：自增 ID 依赖数据库 AUTO_INCREMENT，分库分表、多实例写入时会冲突；雪花 ID 由算法应用侧生成，全局唯一、趋势递增、不依赖数据库，且对 B+ 树索引友好。CityGo 所有实体都用 `@TableId(type = IdType.ASSIGN_ID)`。对外业务单号 `orderNo` 用字符串（`IdWorker.getIdStr()`）避免 JS 长整型精度丢失。
考官想考察什么：是否理解自增 ID 在分布式下的局限、雪花 ID 的优势，以及长整型在前端的精度问题。

**Q3：防超卖为什么用自定义 @Update SQL，而不是 updateById？**
结合 CityGo：`updateById` 是"先读再全量更新"，高并发下两个请求读到同一库存都判定充足，最终扣成负数。`deductStock` 用 `UPDATE ... WHERE stock >= #{quantity}` 单条原子语句，数据库行锁保证同时只有一个请求扣减成功，0 行即库存不足。通用 CRUD 解决"怎么改"，条件更新解决"并发下怎么安全改"，后者必须自己写。
考官想考察什么：能否识别 updateById 的"读改写"竞态，以及条件更新作为原子操作的原理。

**Q4：分页插件的 searchCount 参数有什么用？**
结合 CityGo：MP 分页默认执行 `count` + 查数据两条 SQL。`new Page<>(1, limit, false)` 第三个参数 `false` 即 `searchCount=false`，跳过 count 查询。热门商品只取前 N 条、不关心总数，关掉省一条 SQL。性能敏感或大表分页时值得考虑。
考官想考察什么：是否知道 MP 分页的"两条 SQL"开销，以及按需关闭 count 的优化意识。

**Q5：LambdaQueryWrapper 相比字符串写 SQL 有什么好处？**
结合 CityGo：`Wrappers.lambdaQuery().eq(User::getId, 1)` 用方法引用代替手写列名，编译器校验字段名——字段改名/重构时编译报错，不会漏改；字符串拼 SQL（`eq("id", 1)`）字段名写错要到运行时才发现。CityGo 全项目统一用 Lambda 条件构造器。
考官想考察什么：是否理解"类型安全、编译期校验"相比"字符串拼接"的可维护性优势。

**Q6：怎么避免查询的 N+1 问题？**
结合 CityGo：用 `selectByIds` 批量查询——先 `distinct()` 收集当前页所有 id（分类 id、店铺 id、用户 id），一次 `WHERE id IN (...)` 拉回，`toMap` 组装。如商品列表补分类名、评价列表补店铺名/昵称。20 条记录逐条查是 20 次 SQL，批量查只需 1 次。
考官想考察什么：能否识别 N+1 并给出"批量 in + Map 组装"的标准解法。

**Q7：测试里异步消费者创建的订单，为什么主线程事务回滚覆盖不到？怎么清理？**
结合 CityGo：秒杀订单由 MQ 消费者在**独立线程、独立事务**中异步创建，测试主线程的事务回滚管不到消费者已提交的订单。清理用两重防护：①轮询按当前测试用户 userId 过滤，避免命中历史残留；②`@BeforeEach/@AfterEach` 清理。而且必须用 `jdbcTemplate` 执行 `DELETE` SQL 做**物理硬删**，因为 MP 的 `deleteById` 是软删除（`deleted=1`），残留记录仍会污染后续测试。
考官想考察什么：是否理解"异步线程 + 独立事务"与主线程事务回滚的边界，以及软删除在测试清理中的局限。

---

## f. 开发实战扩展

- **乐观锁插件**：CityGo 用"条件更新"手写乐观锁，生产可用 MP 的 `@Version` + `OptimisticLockerInnerInterceptor` 插件化实现乐观锁，省去手写版本号逻辑。
- **逻辑删除与唯一索引的冲突**：逻辑删除下，唯一索引无法阻止"删除后又插入相同业务键"（因为旧记录还在）。生产对需要唯一约束的字段（如用户名）会用"删除时把唯一键改写成带删除标记的伪值"或改用物理删除。
- **分库分表后的 ID 策略**：雪花 ID 是分库分表友好的，但生产会配合 **ShardingSphere** 做真正的分库分表路由，MP 的 `ASSIGN_ID` 只是 ID 侧的准备。
- **自动填充**：CityGo 的 `createTime/updateTime` 靠数据库默认值填充，生产常用 MP 的 `MetaObjectHandler` 在应用层统一填充创建人/时间、更新人/时间，审计字段更可控。

---

## g. 文件索引

- 分页插件：`src/main/java/com/citygo/config/MybatisPlusConfig.java`
- Mapper 扫描：`src/main/java/com/citygo/CityGoApplication.java`（`@MapperScan("com.citygo.**.mapper")`）
- 逻辑删除全局配置：`src/main/resources/application-dev.yml`（`logic-delete-field` 等）
- 实体注解：`src/main/java/com/citygo/user/entity/User.java` 等（`@TableName`、`@TableId(ASSIGN_ID)`）
- 自定义 SQL：`src/main/java/com/citygo/product/mapper/ProductMapper.java`、`src/main/java/com/citygo/review/mapper/ReviewMapper.java`
- 条件更新实战：`src/main/java/com/citygo/coupon/service/impl/CouponServiceImpl.java`（`setSql`/`apply`）
- 批量查询：各 Service 的 `selectByIds` 用法
- 测试残留教训：`src/test/java/com/citygo/seckill/SeckillControllerTest.java`
