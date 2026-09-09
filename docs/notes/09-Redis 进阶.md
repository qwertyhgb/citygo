# 09 Redis 进阶：缓存三防、分布式锁与接口限流

> 本篇对应 CityGo 中所有「把 Redis 用出花样」的地方：热门商品的缓存三防、跨实例互斥的分布式锁、接口固定窗口限流，以及 Spring Cache 抽象与手写缓存两套体系的失效联动。学完本篇，你要能回答：Redis 除了 `get/set` 还能帮后端解决什么问题，以及每个问题"不用会怎样"。

---

## a. 这个模块是干嘛的

CityGo 是本地生活服务，典型特征是**读多写少**——用户刷热门商品、看店铺详情、抢秒杀的频率远高于商家改数据的频率。如果每次请求都打 MySQL，高峰时数据库就是瓶颈。本模块用 Redis 承担了三件 MySQL 不适合做的事：

1. **缓存**：把热门商品、商品详情、店铺详情等高频读数据挡在数据库前面；
2. **互斥**：多实例部署时，用分布式锁保证"缓存重建"这类动作同一时刻只有一个线程做；
3. **限流**：用计数器给接口加"固定窗口"配额，扛住恶意刷接口和突发流量。

一句话：这一篇讲的是**怎么把 Redis 当成"数据库的盾牌"来用**，而不是当成一个简单的 key-value 存储。

---

## b. 文件一览

| 文件路径 | 职责 |
|---------|------|
| `config/RedisCacheConfig.java` | Spring Cache 抽象配置：`@EnableCaching` + `RedisCacheManager`，默认 TTL 10 分钟、key 前缀「缓存名::」、值 JSON 序列化 |
| `product/service/impl/ProductServiceImpl.java` | 商品缓存核心：`getPublicDetail` 用 `@Cacheable`，`getHotProducts` 手写缓存三防，多个 `@CacheEvict` 入口 + `evictHotProductsCache()` |
| `shop/service/impl/ShopBrowseServiceImpl.java` | 店铺详情 `@Cacheable("shopDetail")` + `evictDetailCache` 失效入口 |
| `merchant/service/impl/ShopServiceImpl.java` | 店铺修改时 `@CacheEvict("shopDetail")` 联动失效 |
| `common/redis/RedisLockUtil.java` | 分布式锁工具：`SET key value EX NX` 加锁 + Lua 原子解锁 |
| `common/annotation/RateLimit.java` | 接口限流注解：`limit`（默认 10）、`windowSeconds`（默认 1） |
| `common/aspect/RateLimitAspect.java` | 限流切面：Redis INCR 计数固定窗口，超阈值抛 429 |
| `auth/security/JwtAuthenticationFilter.java` | 登录态 Redis 双校验：`citygo:login:token:{token} -> userId` |
| `auth/service/impl/AuthServiceImpl.java` | 登录写 Redis 登录态、登出删除，TTL 与 JWT 一致 |

---

## c. 先懂概念

- **缓存穿透**：请求一个「数据库里根本不存在」的数据，缓存里也没有，于是每次都穿透缓存直接打到 DB。攻击者可以用不存在的 id 刷爆数据库。
- **缓存击穿**：某个「热点 key」在过期的瞬间，海量并发同时回源查 DB，把 DB 打挂。注意它和穿透的区别：击穿的数据**在库里是存在的**，只是那一刻缓存刚好过期。
- **缓存雪崩**：大量 key 在**同一时刻集中过期**（比如都设置了 5 分钟），过期瞬间请求全部落到 DB，造成瞬时压力激增。
- **分布式锁**：单机 `synchronized`/`ReentrantLock` 只能锁住同一个 JVM 内的线程；服务多实例部署时，需要一把所有实例都认的锁，Redis 作为共享外部存储天然胜任。
- **固定窗口限流**：把时间切成固定长度窗口（如 1 秒），窗口内计数，超过阈值拒绝。实现最简单，但窗口边界处会有"双倍流量"毛刺。

---

## d. 逐个详解

### d1. RedisCacheConfig：为什么要套 Spring Cache 抽象

CityGo 没有到处手写"先查缓存再查库"，而是用 Spring 的声明式缓存抽象。`RedisCacheConfig` 做了三件事：

1. `@EnableCaching` 打开缓存开关，让 `@Cacheable`/`@CacheEvict` 注解能被 `CacheInterceptor` 拦截生效；
2. 定义 `RedisCacheManager`，默认 `entryTtl(Duration.ofMinutes(10))`——所有缓存默认 10 分钟过期；
3. 指定 key 前缀 `CacheKeyPrefix.simple()`（即「缓存名::参数」，如 `shopDetail::123`）和值序列化器。

**为什么值要用 `GenericJacksonJsonRedisSerializer` 而不是 JDK 序列化**：JDK 序列化出来的是二进制字节，不可读、强绑定 Java 类结构，出了 BUG 你用 `redis-cli` 都看不出里面是什么；JSON 则可读、可跨语言、方便排查。Generic 版会在 JSON 里夹带 `@class` 类型信息，反序列化时据此还原成正确的 VO 类型——这是 Spring Cache 这种「Object 进出」场景的必须品。

**坑在哪**：代码里用的是 `enableUnsafeDefaultTyping()`，允许任意类带 `@class` 反序列化。这在生产是**有反序列化注入风险的**，注释里也写明了——本项目 Redis 是可信内网、只缓存自有 VO，属学习场景可接受的取舍，生产应改成 `BasicPolymorphicTypeValidator` 白名单。另一个细节：`disableCachingNullValues()` 关闭了空值缓存，因为详情接口查不到直接抛异常、不缓存 null，空值缓存只在"热门商品"手写缓存里做。

### d2. getPublicDetail / getDetail：@Cacheable 的声明式用法

`ProductServiceImpl.getPublicDetail` 上加 `@Cacheable(cacheNames = "productDetail", key = "#id")`，`ShopBrowseServiceImpl.getDetail` 上加 `@Cacheable(cacheNames = "shopDetail", key = "#id")`。

执行逻辑：方法被调用前，Spring 先拼出 key `productDetail::{id}` 去 Redis 查，**命中就直接返回缓存值，方法体根本不执行**；未命中才执行方法体查库，并把返回值回填缓存。

**为什么这么写省事**：业务代码里看不到任何缓存样板代码，方法体保持纯粹的"查库 + 脱敏 + 校验"，缓存逻辑全部由注解完成，读写解耦。

**坑在哪**：`@Cacheable` 只能处理「方法参数是 key、返回值是 value」的简单场景。像热门商品这种需要"空值标记 + 互斥锁 + 随机 TTL"的复杂策略，注解表达不了，所以才有了 d3 的手写缓存。

### d3. getHotProducts：手写缓存三防（本篇核心）

热门商品是**热点中的热点**，如果只写个 `@Cacheable`，一旦 key 过期瞬间就会被打爆。`getHotProducts` 用 `StringRedisTemplate` 手写，完整演示了三个经典问题的对策。关键常量在类顶部：

- `HOT_PRODUCTS_KEY = "citygo:cache:hotProducts"`（缓存 key）
- `HOT_PRODUCTS_LOCK_KEY = "citygo:lock:hotProducts"`（重建互斥锁 key）
- `HOT_PRODUCTS_TTL = 5 分钟`、`EMPTY_TTL = 30 秒`、`EMPTY_MARKER = ""`（空值标记）

方法走读（分三步）：

**第一步，查缓存**：`opsForValue().get(HOT_PRODUCTS_KEY)`，非 null 即命中；若值为空字符串 `""`，说明之前查库结果是空、这里存的是**空值标记**，直接返回空列表——这就是**防穿透**：不存在的数据也缓存一个短 TTL 的"空"值，让后续请求不再打 DB。

**第二步，抢锁重建**：未命中时调用 `redisLockUtil.tryLock(HOT_PRODUCTS_LOCK_KEY, 3, token)` 抢互斥锁（3 秒过期），只有抢到的线程负责回源查库回填缓存——这就是**防击穿**。抢到锁后先做 **double-check**：再查一次缓存，因为抢锁期间可能别的线程已经回填好了，避免重复回源。之后 `queryHotFromDb` 查库：

- 结果为空 → 写空值标记 `set(HOT_PRODUCTS_KEY, "", 30s)`，防穿透；
- 结果非空 → 写 JSON 缓存，TTL 用 `randomTtl()`。

**第三步，抢锁失败降级**：抢不到锁说明别人正在重建，`sleep(100)` 后重试 3 次再查缓存；重试仍无缓存就**降级直接查库返回**——注释写得很清楚："宁可多查库，也不阻塞请求"。这是生产必须有的兜底，否则锁挂了所有请求都卡死。

**randomTtl() 防雪崩**：`HOT_PRODUCTS_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(0, 61))`，即 5 分钟 + 随机 0~60 秒。每个 key 的过期时间被打散，避免大量 key 同一秒集中过期——这就是**防雪崩**。

**queryHotFromDb 的两个细节**：一是只查 `status=1` 的上架商品、按 `sales desc, id desc` 排序；二是 `new Page<>(1, Math.max(limit, 1), false)` 第三个参数 `false` 是 `searchCount=false`，**省掉一条 count 查询**（只取前 N 条，不需要知道总数）。

### d4. RedisLockUtil：SET NX EX + Lua 原子解锁

`RedisLockUtil` 是 CityGo 的分布式锁工具，加锁用 `setIfAbsent(key, token, Duration.ofSeconds(expireSeconds))`，底层就是 Redis 的原子命令 `SET key value NX EX seconds`：

- `NX`：只在 key 不存在时才写入，保证互斥——谁先 SET 成功谁拿到锁；
- `EX`：给锁设过期时间，防止持锁方宕机后锁永远不释放（死锁）。

**value 为什么用 UUID**：`tryLock` 自动生成 `UUID.randomUUID().toString()` 作为持有者标识。解锁时先比对 value 再删除，防止"锁已超时被别人拿走、自己却去删锁"的误删。

**解锁为什么必须用 Lua**：解锁 = 「GET 比对 value + DEL」两步。如果分开执行，在比对的瞬间锁可能刚好超时被其他线程获取，此时再 DEL 就会**误删别人的锁**。`RedisLockUtil` 里的 `UNLOCK_SCRIPT` 是一段 Lua：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
```

Lua 脚本在 Redis 服务端**一次性原子执行**，比对与删除之间没有间隙，杜绝误删。

**与数据库条件更新的分工**：类注释里专门对比了两者——数据库条件更新（`UPDATE ... WHERE stock >= ?`）解决的是**数据一致性**问题，锁的粒度是"某一行"，天然伴随事务，适合防超卖；Redis 分布式锁解决的是**跨实例的业务互斥**问题（如缓存重建、唯一性校验），粒度是"某个业务 key"，更轻量、不依赖数据库事务。两者不互相替代。

### d5. 两套缓存体系的失效联动

CityGo 同时存在**两套缓存体系**，失效方式完全不同，这是最容易踩坑的地方：

| 体系 | 代表 | 失效方式 |
|------|------|---------|
| Spring Cache | `productDetail::{id}`、`shopDetail::{id}` | `@CacheEvict(cacheNames=..., key=...)` |
| 手写缓存 | `citygo:cache:hotProducts` | `stringRedisTemplate.delete(key)` |

**为什么不能混用**：`@CacheEvict` 失效的是 Spring Cache 管理的 key（带「缓存名::」前缀）；而热门商品是手写 `StringRedisTemplate` 存的、key 是自定义字符串，两套 key 空间不同，所以 `evictHotProductsCache()` 必须手动 `delete(HOT_PRODUCTS_KEY)`。`ProductServiceImpl` 里这个方法上方的注释专门强调了这一点。

**失效时机**（保证写后一致性）：

- `ProductServiceImpl.update / updateStatus / updateStock` 上都标 `@CacheEvict(cacheNames="productDetail", key="#id")`，同时在方法体里调 `evictHotProductsCache()`——因为商品名/图/上下架/库存变化会影响详情**和**热门列表两处缓存。
- `ShopServiceImpl.update` 标 `@CacheEvict("shopDetail")`，店铺信息改了立即失效店铺详情缓存。
- **旁路写操作的失效**：`OrderServiceImpl` 下单扣库存走的是 `ProductMapper.deductStock`（自定义 SQL），**绕过了 ProductService**，所以 `ProductServiceImpl` 提供了空方法体的 `evictProductDetail(Long productId)`（标 `@CacheEvict`），由下单成功方主动调用，保证写后缓存一致。这是"缓存失效入口"的经典设计：**谁做了旁路写，谁负责通知失效**。

### d6. RateLimitAspect：固定窗口限流（Lua 原子自增 + 真实 IP 解析）

`@RateLimit` 注解（`limit` 默认 10、`windowSeconds` 默认 1）配合 `RateLimitAspect` 切面，给接口加"1 秒最多 10 次"的配额。切面逻辑：

1. **客户端主体判定（支持反向代理）**：拼 key `citygo:rate:{subject}:{类名.方法名}`。`subject` 优先取已认证 `userId`，未登录时通过 `clientIp()` 依次解析 `X-Forwarded-For`、`X-Real-IP` 头（取第一个非空真实 IP），兜底才用 `request.getRemoteAddr()`——**防止 Nginx 反向代理下所有请求被判定为同一网关 IP 而误限流**；
2. **Lua 脚本原子计数与设过期**：
   ```lua
   local count = redis.call('incr', KEYS[1])
   if count == 1 then
       redis.call('expire', KEYS[1], ARGV[1])
   end
   return count
   ```
   **为什么必须用 Lua 脚本**：如果先 `incr`，再在 Java 代码里 `expire`，在两者之间若 JVM 进程崩溃、网络中断或重启，该 key 将永远没有过期时间（变成**永久死 key**），导致用户永远被拦截。Lua 脚本保证了 `INCR` 和首次 `EXPIRE` 的**绝对原子性**；
3. 计数 `> limit` 则抛 `BizException(ErrorCode.TOO_MANY_REQUESTS)`，对应 HTTP 429。

**坑在哪**：切面注释里点破了固定窗口的缺陷——**窗口边界毛刺**。比如 1 秒窗口，请求集中在前 1 秒的末尾 0.5 秒 + 后 1 秒的开头 0.5 秒，实际上在 1 秒内放行了 2 倍配额。生产更平滑的替代是滑动窗口或令牌桶，但固定窗口胜在实现最简单、Redis 单个 key 配合 Lua 脚本即可搞定。

### d7. 登录态 Redis：为什么 JWT 无状态还要 Redis

这不是缓存，但同属"Redis 的进阶用法"。`JwtAuthenticationFilter` 拿到 Bearer token 后，先 `jwtUtil.parseUserId(token)` 解析签名，**再查 `citygo:login:token:{token}` 是否存在**——只有 JWT 有效**且** Redis 里有对应登录态，才认证通过。存的值是 `userId:ROLE1,ROLE2` 格式，过滤器顺带从冒号后解析角色，**请求链路零查库**（角色加载不需要再查 user_role/role 表，详见 03 篇）。

**为什么 JWT 本身无状态还要 Redis**：JWT 签发后无法主动作废，用户改了密码、被封号、或主动登出后，旧 token 在过期前依然"合法"。用 Redis 存一份登录态，**登出时直接删 key**（`AuthServiceImpl` 登出逻辑），旧 token 立刻失效——这就是"无状态 JWT + 有状态 Redis"的组合拳：JWT 负责自包含、免查库，Redis 负责可撤销、可强制下线。TTL 与 JWT 有效期一致，登录时写入、登出时删除。

---

## e. 面试真题

**Q1：缓存穿透是什么？你们项目怎么解决的？**
结合 CityGo：穿透是大量请求查「不存在的数据」，缓存没有、DB 也没有，请求全打到 DB。CityGo 在 `ProductServiceImpl.getHotProducts` 里，查库结果为空时，不直接返回，而是 `set(HOT_PRODUCTS_KEY, "", 30s)` 写一个**空值标记**（短 TTL 30 秒），下次命中空串直接返回空列表，不再打 DB。
考官想考察什么：是否真的理解穿透的成因是"不存在的数据"，以及空值缓存 + 短 TTL 这个最基础的解法。

**Q2：缓存击穿和穿透有什么区别？击穿怎么解决？**
结合 CityGo：击穿是热点 key 过期瞬间海量并发回源，数据本身在库里存在。CityGo 用**互斥锁 + double-check**：抢到 `citygo:lock:hotProducts` 锁的线程负责查库回填，抢锁后先 double-check 一次缓存（防止锁期间已被别人回填），抢不到锁的线程 `sleep(100)` 重试 3 次，再没有就降级查库返回。
考官想考察什么：能否区分击穿（热点 key、数据存在）与穿透（数据不存在），以及互斥锁的完整闭环（含 double-check 和降级兜底）。

**Q3：缓存雪崩怎么防？**
结合 CityGo：雪崩是大量 key 同一时刻集中过期。CityGo 的 `randomTtl()` 给每个 key 的 TTL 叠加随机 0~60 秒偏移，即「5 分钟 + 随机 60 秒」，把过期时间打散。生产还会加"热点数据永不过期 + 后台异步刷新""多级缓存""限流降级"等组合手段。
考官想考察什么：能不能说出雪崩的根因是"同时过期"，并给出"随机 TTL 打散"这个最常用答案。

**Q4：分布式锁怎么加锁、怎么释放？为什么释放要用 Lua？**
结合 CityGo：`RedisLockUtil` 用 `setIfAbsent(key, UUID, Duration)` 即 `SET key value NX EX` 加锁，NX 保证互斥、EX 防死锁、UUID 作持有者标识；释放用 Lua 脚本 `if get(key)==token then del(key)` 原子执行。分开执行「GET + DEL」会在比对瞬间锁超时被他人获取时误删别人的锁，Lua 在服务端一次性原子完成，杜绝这个间隙。
考官想考察什么：能否写出 `SET NX EX` 和 Lua 释放脚本，并讲清"为什么要 UUID 比对 + 原子释放"这两个分布式锁最容易翻车的点。

**Q5：为什么 JWT 无状态了还要在 Redis 存一份登录态？**
结合 CityGo：JWT 签发后无法主动作废，改密/封号/登出后旧 token 依然"合法"。CityGo 登录时把 `citygo:login:token:{token} -> userId` 写入 Redis（TTL 与 JWT 一致），`JwtAuthenticationFilter` 解析 JWT 后再校验 Redis，登出删 key 即可让 token 立刻失效。JWT 负责自包含免查库，Redis 负责可撤销，两者互补。
考官想考察什么：是否理解 JWT 无状态的最大短板是"不可撤销"，以及"双校验"如何补齐这个短板。

**Q6：固定窗口限流怎么实现？它有什么缺陷？**
结合 CityGo：`RateLimitAspect` 用 Redis `INCR` 计数，首次返回 1 时 `EXPIRE windowSeconds` 开启窗口，超过 `@RateLimit.limit` 抛 429。key 是 `citygo:rate:{用户或IP}:{类名.方法名}`。缺陷是**窗口边界毛刺**——请求卡在前后两个窗口交界处，1 秒内可能放行 2 倍配额。生产更平滑可用滑动窗口或令牌桶。
考官想考察什么：能否说出固定窗口的 INCR+EXPIRE 实现，并主动暴露边界毛刺这个缺陷。

**Q7：Spring Cache 注解缓存和手写 Redis 缓存，失效方式为什么不一样？**
结合 CityGo：`@CacheEvict` 失效的是 Spring Cache 管理的 key（带「缓存名::」前缀），而 `getHotProducts` 是手写 `StringRedisTemplate` 存的、key 自定义，两套 key 空间不同，所以热门缓存必须手动 `stringRedisTemplate.delete(HOT_PRODUCTS_KEY)`，注解失效不了它。CityGo 在 `evictHotProductsCache()` 的注释里明确写了这点。
考官想考察什么：是否理解 Spring Cache 的 key 生成规则，以及"注解失效"与"手动 delete"的适用边界。

**Q8：缓存和数据库的一致性怎么保证？**
结合 CityGo：CityGo 采用「**先改库，再失效缓存**」的旁路失效模式——写操作（`update`/`updateStatus`/`updateStock`）先更新 MySQL，再通过 `@CacheEvict` 失效详情缓存 + `evictHotProductsCache()` 失效热门缓存；对 `OrderServiceImpl` 下单扣库存这种**绕过 Service 的旁路写**，提供空方法体的 `evictProductDetail` 入口，由下单成功方主动调用失效。好处是避免了"先删缓存、并发读又回填旧值"的经典问题。
考官想考察什么：能否讲清"先改库后删缓存"的选择理由，以及"旁路写需要显式失效入口"这个容易被忽略的工程细节。

---

## f. 开发实战扩展

- **互斥锁升级**：CityGo 的 `RedisLockUtil` 用的是"加锁 + 过期"的简单实现，生产会换成 **Redisson 的看门狗（Watchdog）**——锁快过期时自动续期，避免业务执行超过锁 TTL 导致的"锁提前释放、并发穿透"。CityGo 抢锁只设了 3 秒，是因为重建动作足够快，学习场景够用。
- **空值标记的通用化**：手写空值缓存只能覆盖热门商品一处，生产一般用 **布隆过滤器（BloomFilter）** 在缓存前就拦截"一定不存在"的 key，比空值缓存更省内存、覆盖更广；但布隆过滤器有误判率、且删除困难，空值缓存仍是简单场景的首选。
- **限流算法替换**：`@RateLimit` 的固定窗口有边界毛刺，生产高价值接口会换 **滑动窗口**（Redis ZSet 记录每个请求时间戳，`ZREMRANGEBYSCORE` 清理窗口外记录）或 **令牌桶**（Lua 脚本原子取令牌），实现匀速放行。
- **缓存一致性兜底**：`@CacheEvict` 是"尽力而为"的失效，极端情况下（Redis 挂了、删除失败）会出现短时间脏数据。生产会在缓存 TTL 上做文章（TTL 足够短，脏数据很快自愈），或引入 **Canal 监听 binlog** 做最终一致的主动失效。

---

## g. 文件索引

- 缓存三防：`src/main/java/com/citygo/product/service/impl/ProductServiceImpl.java`（`getHotProducts` / `randomTtl` / `queryHotFromDb` / `evictHotProductsCache`）
- Spring Cache 配置：`src/main/java/com/citygo/config/RedisCacheConfig.java`
- 店铺缓存：`src/main/java/com/citygo/shop/service/impl/ShopBrowseServiceImpl.java`（`getDetail` / `evictDetailCache`）
- 店铺失效联动：`src/main/java/com/citygo/merchant/service/impl/ShopServiceImpl.java`（`update` 上的 `@CacheEvict`）
- 分布式锁：`src/main/java/com/citygo/common/redis/RedisLockUtil.java`
- 接口限流：`src/main/java/com/citygo/common/annotation/RateLimit.java` + `src/main/java/com/citygo/common/aspect/RateLimitAspect.java`
- 登录态 Redis：`src/main/java/com/citygo/auth/security/JwtAuthenticationFilter.java`、`src/main/java/com/citygo/auth/service/impl/AuthServiceImpl.java`
- 错误码：`src/main/java/com/citygo/common/exception/ErrorCode.java`（`TOO_MANY_REQUESTS`）
- 下单旁路写失效调用：`src/main/java/com/citygo/order/service/impl/OrderServiceImpl.java`（下单流程中调用 `evictProductDetail`）
