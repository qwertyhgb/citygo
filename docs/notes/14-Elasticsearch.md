# 14 Elasticsearch：IK 分词、双写同步与地理搜索

> 本篇对应 CityGo 的搜索域：店铺/商品的全文搜索、按分类价格过滤、销量评分排序，以及"附近店铺"的地理距离搜索。ES 是 CityGo 唯一一个"独立于 MySQL 的读模型"，它带来的核心问题是**数据一致性**——MySQL 和 ES 两份数据怎么保持一致。学完本篇，你要能回答：什么时候该上 ES、IK 分词怎么配、双写一致性怎么保证、附近店铺怎么搜。

---

## a. 这个模块是干嘛的

CityGo 的用户要搜"附近 5 公里内的火锅店""销量最高的奶茶""50 元以内的商品"。这些需求用 MySQL 的 `LIKE '%火锅%'` 做不到——**全表扫描、无相关性打分、无法高效做地理位置距离计算**。Elasticsearch 是专门的搜索引擎，它的倒排索引让全文检索毫秒级返回，`geo_point` 类型原生支持"按距离过滤和排序"。所以 CityGo 把店铺和商品的搜索读流量全部交给 ES，MySQL 只保留事务和主数据。

一句话：这一篇讲的是**怎么用 ES 构建一套"读走搜索、写走数据库、双写保持一致性"的搜索体系**。

---

## b. 文件一览

| 文件路径 | 职责 |
|---------|------|
| `search/config/ESIndexInitializer.java` | 启动时初始化索引：先 create 空索引、再 putMapping 写映射（IK + geo_point） |
| `search/support/SearchSync.java` | 双写同步助手：`afterCommit` 在事务提交后执行 ES 同步，失败只记 ERROR |
| `search/doc/ProductDoc.java` | 商品搜索文档：IK 字段 productName/description、冗余 shopName/categoryName |
| `search/doc/ShopDoc.java` | 店铺搜索文档：IK 字段 shopName、`@GeoPointField` location 经纬度 |
| `search/repository/ProductSearchRepository.java` + `ShopSearchRepository.java` | Spring Data ES 仓库，负责写索引 |
| `search/service/impl/ProductSearchServiceImpl.java` | 商品搜索：NativeQuery 组合查询 + 降级 |
| `search/service/impl/ShopSearchServiceImpl.java` | 店铺搜索：geo_distance 过滤/排序 + 降级 |
| `search/controller/SearchController.java` | 搜索接口：`/api/search/shops`、`/api/search/products` |

---

## c. 先懂概念

- **倒排索引**：把"文档 → 词"反转为"词 → 文档"的索引结构。搜"火锅"时直接定位到包含"火锅"的文档列表，不用逐条扫描。这是 ES 全文检索快的根本原因。
- **IK 分词器**：中文没有空格分词，"重庆火锅很好吃"需要切成"重庆/火锅/很好/吃"才能检索。IK 是主流中文分词插件，`ik_max_word` 和 `ik_smart` 是它的两种模式。
- **双写（dual-write）**：数据既写 MySQL 又写 ES，保持两份数据一致。难点在于"何时写、写失败怎么办"。
- **geo_point**：ES 的地理坐标类型，存储经纬度，支持 `geo_distance` 查询（某半径内的点）和距离排序。
- **NativeQuery**：Spring Data ES 的高级查询 API，用 `Query`/`SortOptions` 构建器拼复杂的 bool 组合查询，比 Repository 派生方法灵活。

---

## d. 逐个详解

### d1. ESIndexInitializer：ES 9 的索引初始化姿势

`ESIndexInitializer` 实现 `ApplicationRunner`，应用启动时检查 `citygo_shop`/`citygo_product` 索引是否存在，不存在则创建。关键点在"分两步"：

```java
boolean created = idx.create();   // 先建空索引
boolean mapping = idx.putMapping(); // 再写映射
```

**为什么分两步**：注释里说明了——ES 9 中 index mapping 的写入通过 `indexOps().putMapping()` 完成，创建索引本体用 `create()`；映射里含 IK 分析器和 geo_point 字段，必须显式建立，否则用默认映射会丢掉这些类型定义。

**坑在哪**：`@Document(indexName=..., createIndex=false)` 配合这里的手动初始化——关闭了 SDE 启动时自动建索引（`createIndex=false`），改为手动控制，因为自动建索引时机的映射未必按注解生成得准。另一个细节：初始化异常被 catch 住**不阻断应用启动**，后续写入仍会尝试。

### d2. 文档设计：ProductDoc 与 ShopDoc 的冗余艺术

两个文档类是 ES 搜索的"读模型"，设计上有三个共同点：

1. **冗余展示字段**：`ProductDoc` 冗余了 `shopName`、`categoryName`，`ShopDoc` 冗余了 `city`、`district`、`address`。**为什么冗余**：搜索列表要展示店铺名/分类名/城市，如果 ES 文档只存 id，返回结果后还得逐条回 MySQL 查，搜索就失去了意义。冗余让"搜索 + 展示"一趟 ES 全搞定，不再查 MySQL。
2. **IK 字段**：`productName`/`description`/`shopName` 用 `@Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")`——**索引用 ik_max_word（最细切分，召回全）、搜索用 ik_smart（智能切分，结果准）**。这是 IK 的经典搭配：索引阶段切得越细，召回越多；查询阶段切得越智能，噪声越少。
3. **状态字段保留**：`status` 存进文档但不删除下架文档——下架商品 `status=0` 保留文档、搜索时过滤，**便于重新上架时不用重建文档**。

**坑在哪**：冗余字段的代价是"两份数据"，`shopName` 改了，MySQL 和所有冗余它的 ES 文档都要跟着改。CityGo 在商品更新/店铺更新时都做了同步（见 d3），生产要有机制保证冗余字段的最终一致。

### d3. SearchSync：双写一致性怎么保证

`SearchSync.afterCommit(Runnable)` 是 ES 双写的核心，与 10 篇下单消息的 `afterCommit` 同一套路：

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() { runSafely(syncTask); }
});
```

**为什么必须 afterCommit**：ES 不参与本地 MySQL 事务——如果在事务内同步 ES，一旦事务回滚，ES 里会留下"幽灵文档"；反之 ES 写入失败又会拖累本地事务。afterCommit 保证"MySQL 提交成功后才去同步 ES"。

**一致性取舍**：`runSafely` 里 catch 住所有异常只记 ERROR，**主库为准，允许索引短暂不一致**。注释里写明生产可升级为 MQ/Canal 异步同步——afterCommit 是学习项目的够用方案，但它有两个局限：①发同步时进程崩溃会丢；②ES 暂时失败后没有重试补偿。

**同步的调用点**（贯穿全项目）：商品 create/update/上下架/调库存后 `syncProduct`，下单/取消后 `syncProducts`（销量库存变了），评价后 `syncShop`（评分变了），秒杀消费者落库后 `syncProducts`。所有"主库数据变化"的地方都跟着一次 ES 同步。

### d4. ProductSearchServiceImpl：NativeQuery 组合查询

`searchProducts` 用 `NativeQuery` 拼一个 bool 查询，结构清晰：

- **filter 部分**（不参与打分，只过滤）：`status=1`（只搜上架）、`categoryId` term（精确匹配）、`price` range（minPrice/maxPrice 区间）；
- **must 部分**（参与打分）：`keyword` 用 `multiMatch` 在 `productName` 和 `description` 两个 IK 字段上检索——**一个关键词同时匹配两个字段**，商品名匹配到的权重更高。

**filter 和 must 的区别**（面试点）：filter 只过滤不计算相关性分数，且 ES 会缓存 filter 结果，性能更好；must 参与打分。所以"确定性条件"（状态、分类、价格）放 filter，"模糊检索"（关键词）放 must。

**排序白名单**：`buildSort` 用 switch 白名单映射——`sales` 按销量、`price_asc/price_desc` 按价格、`default` 按 `_score` 相关度。**绝不把用户输入直接拼进排序字段**，与 MySQL 侧的排序白名单思路一致（防注入/防非法字段）。

**结果脱敏**：`toVO` 里 `vo.setStock(null)`，公开搜索不暴露商家库存。

**降级策略**：整个搜索包在 try-catch 里，`catch (Exception)` 返回空列表——ES 挂了（连接失败等）**接口不报错、不阻塞**，降级返回空。这是搜索接口的基本素养：搜索是"体验增强"，不能因为搜索引擎挂了导致核心功能不可用。

### d5. ShopSearchServiceImpl：geo_point 附近店铺搜索

`searchShops` 在商品搜索的基础上加了地理能力，是本篇的亮点：

**距离过滤**（filter 里的 geo_distance）：

```java
GeoLocation loc = GeoLocation.of(gl -> gl.latlon(l -> l.lat(nearLat).lon(nearLng)));
filters.add(Query.of(q -> q.geoDistance(g -> g.field("location")
        .distance(distanceKm + "km").location(loc))));
```

传入用户经纬度和半径（默认 5 公里，`SearchController` 里 `distanceKm` 默认 5），ES 返回 `location` 字段在半径内的店铺。

**距离排序**（sort 里的 geo_distance）：

```java
SortOptions.of(o -> o.geoDistance(g -> g.field("location")
        .location(loc).unit(DistanceUnit.Kilometers).order(SortOrder.Asc)));
```

`sort=distance` 时按距离升序，最近的在最前。**距离过滤与排序解耦**——可以只过滤不按距离排序，也可以过滤后按评分/销量排。

**GeoPoint 的坑**：`toDoc` 里 `new GeoPoint(shop.getLatitude(), shop.getLongitude())`——构造参数是**先纬度后经度**，而 ES 的 geo_point 存储格式是 `[经度, 纬度]`。`ShopDoc` 注释专门点破了这个最容易搞反的点：`GeoPoint(lat,lon)` 由 SDE 映射成 `[lon,lat]` 存索引，写代码时参数顺序和存储顺序相反，记错了就会把店铺定位到地球另一边。

**距离排序的兜底**：`buildSort` 里 `sort=distance` 但没传经纬度时，回退默认相关度排序——不能因为参数缺失就崩溃。

### d6. Repository 与 ElasticsearchOperations 的分工

CityGo 同时用了两种 ES 访问方式：

- `ElasticsearchRepository`（`ProductSearchRepository`/`ShopSearchRepository`）：负责**文档写索引**（`save`/`saveAll`），继承了一堆现成的 CRUD；
- `ElasticsearchOperations` + `NativeQuery`：负责**复杂查询**（搜索时的组合查询）。

注释里点明了设计原则：**ES 管索引，MySQL 管主数据与事务**——ES 是搜索索引，MySQL 是唯一事实源。所以写路径是"MySQL 事务里改主数据 → afterCommit 后 `repository.save` 同步文档"，读路径是"`operations.search` 直接查 ES"。

---

## e. 面试真题

**Q1：为什么搜索要用 ES 而不是 MySQL 的 LIKE？**
结合 CityGo：`LIKE '%火锅%'` 是逐行全表扫描、无法打分排序、也无法高效做"附近 5 公里"的地理距离计算。ES 靠倒排索引让全文检索毫秒级返回，`geo_point` 类型原生支持距离过滤排序。CityGo 把店铺/商品搜索读流量全交给 ES，MySQL 只留事务和主数据。
考官想考察什么：能否讲清 ES 相对 MySQL 的本质优势（倒排索引、打分、geo），以及"搜索走 ES、事务走 MySQL"的分工。

**Q2：IK 分词器的 ik_max_word 和 ik_smart 有什么区别？怎么搭配？**
结合 CityGo：ik_max_word 是"最细切分"，把"重庆火锅很好吃"切成尽可能多的词，召回率高；ik_smart 是"智能切分"，切成最合理的词，噪声少。CityGo 的文档字段用 `analyzer=ik_max_word`（索引阶段切得细、召回全）+ `searchAnalyzer=ik_smart`（查询阶段切得准、结果精准）——索引与搜索用不同粒度。
考官想考察什么：是否理解"索引用最细、搜索用智能"这一 IK 的经典搭配，而不是笼统说"用 IK"。

**Q3：ES 和 MySQL 怎么保持数据一致？为什么 afterCommit 才同步？**
结合 CityGo：双写——MySQL 事务改主数据，`SearchSync.afterCommit` 在事务提交成功后同步 ES 文档。不在事务内同步的原因：事务回滚会在 ES 留下幽灵文档，ES 写失败又会拖累本地事务。一致性取舍是"主库为准，允许索引短暂不一致"，ES 同步失败只记 ERROR，生产升级为 MQ/Canal 异步补偿。
考官想考察什么：能否讲清"事务内同步"的两种风险，以及 afterCommit 方案"最终一致、允许短暂不一致"的取舍边界。

**Q4：附近店铺距离搜索怎么实现？**
结合 CityGo：文档用 `@GeoPointField location` 存经纬度（`GeoPoint(lat, lon)` 映射为 `[经度,纬度]`），查询用 `geo_distance` filter 过滤半径内的点，排序用 `geo_distance` sort 按距离升序。过滤与排序解耦，可以只过滤不按距离排。注意 GeoPoint 构造参数是"先纬度后经度"，与存储格式 `[经度,纬度]` 相反，搞反了店铺会定位到错误位置。
考官想考察什么：能否说出 geo_point 的过滤/排序用法，以及经纬度顺序这个最经典的坑。

**Q5：NativeQuery 里 filter 和 must 有什么区别？**
结合 CityGo：filter 只过滤不参与相关性打分，且结果会被 ES 缓存、性能更好；must 参与打分。所以 CityGo 把确定性条件（status=1、categoryId、price 区间、geo 距离）放 filter，把模糊检索（multiMatch 关键词）放 must——确定性条件不需要打分，模糊检索才需要相关性分数。
考官想考察什么：能否理解 bool 查询中 filter（不打分、可缓存）与 must（打分）的性能语义差异。

**Q6：商品搜索怎么做一个关键词同时匹配多个字段？**
结合 CityGo：用 `multiMatch` 指定 `fields("productName", "description")`，一个关键词同时在商品名和描述两个 IK 字段上检索，商品名命中权重更高。排序默认按 `_score` 相关度降序，也可以白名单切换到销量/价格排序。
考官想考察什么：能否说出 multiMatch 的用途，以及"多字段检索 + 相关性排序"的组合。

**Q7：ES 挂了搜索接口怎么办？**
结合 CityGo：降级返回空列表——两个 SearchServiceImpl 的搜索方法都包 try-catch，`catch (Exception)` 记 ERROR 后返回空 `PageVO`，接口不报错、不阻塞。原则是"搜索是体验增强，不能因为搜索引擎挂了导致核心功能不可用"。生产还会加熔断、限流和 MySQL 兜底查询。
考官想考察什么：是否有"降级兜底"意识，把搜索定位为"可降级的增强能力"而非核心路径。

**Q8：ES 文档里为什么冗余 shopName、categoryName？冗余的代价是什么？**
结合 CityGo：搜索列表要展示店铺名/分类名/城市，文档只存 id 的话返回结果后还得逐条回 MySQL 查，搜索就失去意义了。冗余让"搜索 + 展示"一趟 ES 搞定。代价是两份数据——shopName 改了，MySQL 和所有冗余它的文档都要同步更新，CityGo 在商品/店铺更新时都同步了 ES，生产需有机制保证冗余字段最终一致。
考官想考察什么：能否理解"以冗余换查询性能"的权衡，以及冗余带来的数据一致性成本。

---

## f. 开发实战扩展

- **双写升级为异步对账**：afterCommit 是"尽力同步"，生产会用 **Canal 监听 MySQL binlog** 推送变更到 MQ，由消费端同步 ES——解耦更彻底、可重试、可对账，彻底避免"同步时进程崩溃丢更新"。
- **冗余字段的自动维护**：店铺名这种被多个文档冗余的字段，生产用 **ES pipeline + parent-child 关联** 或专门的"冗余字段刷新任务"，避免改一个店铺名要手工扫所有商品文档。
- **搜索降级全链路**：生产会给搜索加 **Hystrix/Sentinel 熔断 + 限流**，ES 异常时熔断开关自动切到 MySQL 兜底查询或缓存，而不是简单返回空。
- **地理位置精度优化**：`geo_distance` 在大数据量下较慢，生产对"附近店铺"会用 **GeoHash 前缀过滤 + 距离精算** 两段式，先粗筛再精算，性能更好。

---

## g. 文件索引

- 索引初始化：`src/main/java/com/citygo/search/config/ESIndexInitializer.java`
- 双写助手：`src/main/java/com/citygo/search/support/SearchSync.java`
- 文档模型：`src/main/java/com/citygo/search/doc/ProductDoc.java`、`src/main/java/com/citygo/search/doc/ShopDoc.java`
- 仓库：`src/main/java/com/citygo/search/repository/`（ProductSearchRepository / ShopSearchRepository）
- 搜索实现：`src/main/java/com/citygo/search/service/impl/ProductSearchServiceImpl.java`、`src/main/java/com/citygo/search/service/impl/ShopSearchServiceImpl.java`
- 接口：`src/main/java/com/citygo/search/controller/SearchController.java`
- ES 连接配置：`src/main/resources/application-dev.yml`（`spring.elasticsearch.uris: http://localhost:9200`）
- 同步调用点：商品/订单/秒杀/评价各 Service 中的 `searchSync.afterCommit(...)` 调用
