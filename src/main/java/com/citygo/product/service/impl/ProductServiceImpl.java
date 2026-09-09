package com.citygo.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citygo.category.entity.Category;
import com.citygo.category.mapper.CategoryMapper;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.page.PageVO;
import com.citygo.common.redis.RedisLockUtil;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.merchant.service.ShopService;
import com.citygo.product.dto.ProductBrowseQuery;
import com.citygo.product.dto.ProductCreateRequest;
import com.citygo.product.dto.ProductMyPageQuery;
import com.citygo.product.dto.ProductStatusRequest;
import com.citygo.product.dto.ProductStockRequest;
import com.citygo.product.dto.ProductUpdateRequest;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import com.citygo.search.service.ProductSearchService;
import com.citygo.search.support.SearchSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 商品域服务实现 - 面向新手的详细说明版。
 *
 * <h3>这个类是干什么的？</h3>
 * 商品模块的核心业务逻辑层，是"商品管家"：
 * 商家侧（创建/修改/上下架/改库存/我的商品列表）和用户侧
 * （公开列表/详情/热门商品）的所有商品操作都经过这里。
 *
 * <h3>核心设计思想</h3>
 * <ol>
 *   <li><b>两级归属校验</b>：商品属于店铺，店铺属于商家。
 *       操作前先确认"当前用户是商家"（requireMerchant），
 *       再确认"商品所在的店铺属于这个商家"（requireOwnedProduct → requireOwnedShop），
 *       防止商家 A 越权修改商家 B 的商品（返回 403）</li>
 *   <li><b>三方数据联动</b>：商品数据同时存在于三个地方——
 *       MySQL（主存储，最权威）、Redis 缓存（加速详情/热门查询）、
 *       Elasticsearch（提供搜索）。写操作改完 MySQL 后，
 *       必须同步失效缓存 + 同步 ES，否则用户会读到旧数据</li>
 *   <li><b>库存脱敏</b>：库存（stock）是商家内部经营数据，
 *       所有公开（用户端）接口的返回值都把 stock 置为 null，
 *       只有商家自己的"我的商品"接口才返回真实库存</li>
 *   <li><b>缓存三防</b>：热门商品接口手写实现了
 *       防穿透（空值标记）、防击穿（互斥锁 + double-check）、
 *       防雪崩（TTL 随机偏移）三大经典策略，详见 getHotProducts</li>
 *   <li><b>事务保护</b>：所有写操作加 @Transactional，
 *       ES 同步通过 SearchSync.afterCommit 延迟到事务提交后执行</li>
 * </ol>
 *
 * <h3>数据流转路径</h3>
 * <pre>
 * 商家创建商品：
 *   Controller → create → 校验商家身份 → 校验分类存在 → 校验店铺归属
 *   → 插入 MySQL → （提交后）同步 ES → 返回 VO
 *
 * 用户浏览商品列表：
 *   Controller → pagePublic → 校验店铺正常 → 组装查询条件（白名单排序）
 *   → 分页查 MySQL → 转 VO + 库存脱敏 → 返回
 *
 * 用户查热门商品：
 *   Controller → getHotProducts → 查 Redis 缓存
 *   → 命中：直接返回 / 未命中：抢锁 → 查库 → 回填缓存 → 返回
 * </pre>
 *
 * <h3>类内方法布局约定</h3>
 * - 上半部分：公共方法（实现 ProductService 接口），对外暴露的业务能力
 * - 下半部分：私有方法（辅助方法），内部复用的校验/工具逻辑
 * 阅读时先看"能做什么"，再看"怎么做的"。
 *
 * @see ShopServiceImpl 店铺管理（归属校验模式的源头，本类复用其思路）
 * @see SearchSync      ES 同步的事务时序保障
 */
@Service  // Spring 组件注解：标记这是一个服务层 Bean，由 Spring 容器管理
public class ProductServiceImpl implements ProductService {

    /** 日志对象：记录缓存降级等不影响业务但需要关注的异常（排查问题的"黑匣子"） */
    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    // ============================================================
    // 常量定义（手写缓存相关的 key 与 TTL，集中定义避免魔法字符串）
    // ============================================================

    /** 热门商品缓存 key（手写缓存，见 getHotProducts）。前缀 citygo: 是项目 Redis key 规范 */
    private static final String HOT_PRODUCTS_KEY = "citygo:cache:hotProducts";

    /** 热门商品缓存重建互斥锁 key：防止缓存失效瞬间大量线程同时查库（防击穿） */
    private static final String HOT_PRODUCTS_LOCK_KEY = "citygo:lock:hotProducts";

    /** 热门商品缓存基础 TTL：5 分钟（过期时间不宜太长，热门榜单要相对新鲜） */
    private static final Duration HOT_PRODUCTS_TTL = Duration.ofMinutes(5);

    /** 空值标记缓存 TTL：30 秒（防穿透的短缓存，太短没意义、太长影响新商品上榜） */
    private static final Duration EMPTY_TTL = Duration.ofSeconds(30);

    /** 空值标记（无热门商品时缓存空字符串，区别于"未命中"的 null） */
    private static final String EMPTY_MARKER = "";

    /** 单页条数上限：防止 pageSize=999999 一次拉取过多数据拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 50;

    /**
     * 热门榜单的两个上限合一：单次请求最多返回的条数（limit 夹逼上界），
     * 以及缓存重建时固定的"权威长度"——缓存始终存 top 50，按请求 limit 切片返回。
     */
    private static final int MAX_HOT_LIMIT = 50;

    // ============================================================
    // 依赖注入的组件（通过构造器注入，这是 Spring 推荐的方式）
    // ============================================================

    /** 商品数据访问层：负责 product 表的 CRUD */
    private final ProductMapper productMapper;

    /** 分类数据访问层：创建/修改商品时校验分类是否存在 */
    private final CategoryMapper categoryMapper;

    /** 店铺数据访问层：公开列表校验店铺状态 */
    private final ShopMapper shopMapper;

    /** 店铺服务：查询店铺归属的商家 ID（复用店铺模块的归属查询能力） */
    private final ShopService shopService;

    /** 商家服务：把"当前登录用户"解析成"商家身份" */
    private final MerchantService merchantService;

    /** Redis 客户端（字符串序列化）：手写热门商品缓存的读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /** Redis 分布式锁工具：热门缓存重建时的互斥锁（tryLock / unlock） */
    private final RedisLockUtil redisLockUtil;

    /** JSON 序列化器：热门商品列表 ↔ JSON 字符串（缓存里存的是 JSON） */
    private final JsonMapper jsonMapper;

    /** 商品搜索服务：把商品数据同步到 Elasticsearch */
    private final ProductSearchService productSearchService;

    /** 搜索同步工具：保证 ES 同步在事务提交后执行（避免回滚留下 ES 幽灵数据） */
    private final SearchSync searchSync;

    /**
     * 构造器注入（Spring 推荐方式）。
     *
     * <p>Spring 启动时发现只有一个构造器，会自动把容器里的
     * 各个 Bean 注入进来，不需要 @Autowired 注解。</p>
     *
     * @param productMapper         商品 Mapper
     * @param categoryMapper        分类 Mapper
     * @param shopMapper            店铺 Mapper
     * @param shopService           店铺服务（查店铺归属）
     * @param merchantService       商家服务（用户 → 商家身份）
     * @param stringRedisTemplate   Redis 客户端（手写缓存）
     * @param redisLockUtil         Redis 分布式锁工具
     * @param jsonMapper            JSON 序列化器
     * @param productSearchService  ES 商品同步服务
     * @param searchSync            事务提交后同步调度器
     */
    public ProductServiceImpl(ProductMapper productMapper,
                              CategoryMapper categoryMapper,
                              ShopMapper shopMapper,
                              ShopService shopService,
                              MerchantService merchantService,
                              StringRedisTemplate stringRedisTemplate,
                              RedisLockUtil redisLockUtil,
                              JsonMapper jsonMapper,
                              ProductSearchService productSearchService,
                              SearchSync searchSync) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.shopMapper = shopMapper;
        this.shopService = shopService;
        this.merchantService = merchantService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisLockUtil = redisLockUtil;
        this.jsonMapper = jsonMapper;
        this.productSearchService = productSearchService;
        this.searchSync = searchSync;
    }

    // ============================================================
    // 公共方法（实现 ProductService 接口）
    // ============================================================

    /**
     * 创建商品 - 完整流程详解。
     *
     * <h3>业务场景</h3>
     * 商家开店后，往店铺里上架商品。每个商品必须挂在"一个店铺 + 一个分类"下。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li><b>校验商家身份</b>：当前登录用户必须是商家（有 merchant 记录）</li>
     *   <li><b>校验分类存在</b>：categoryId 必须在 category 表里存在，
     *       否则商品会挂到"幽灵分类"下，分类页展示会出问题</li>
     *   <li><b>校验店铺归属</b>：shopId 对应的店铺必须属于当前商家
     *       ——这是防越权的关键，商家 A 不能往商家 B 的店铺里塞商品</li>
     *   <li><b>组装实体并插入</b>：新商品默认上架（status=1）、销量 0</li>
     *   <li><b>同步 ES</b>：事务提交后把商品写入搜索索引，让用户能搜到</li>
     * </ol>
     *
     * <h3>校验顺序为什么是"分类在前、归属在后"？</h3>
     * 分类校验是"数据合法性"（参数错了），店铺归属是"权限"（你没资格）。
     * 先报参数错误对用户更友好，也避免无权限请求探测店铺信息。
     *
     * @param request       创建请求体（店名、分类、价格、库存等）
     * @param currentUserId 当前登录用户 ID（从 JWT 解析，Controller 传入）
     * @return 创建后的商品视图对象（含自动生成的雪花 ID）
     * @throws BizException MERCHANT_NOT_FOUND（不是商家）/
     *                      CATEGORY_NOT_FOUND（分类不存在）/
     *                      SHOP_NOT_FOUND 或 UNAUTHORIZED_OPERATION（店铺归属校验失败）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)  // 开启事务，任何异常都回滚
    public ProductVO create(ProductCreateRequest request, Long currentUserId) {
        // 步骤 1: 校验商家身份
        Merchant merchant = requireMerchant(currentUserId);
        // 步骤 2: 分类存在性校验
        if (categoryMapper.selectById(request.getCategoryId()) == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        // 步骤 3: 店铺归属校验（店铺必须存在且属于当前商家）
        requireOwnedShop(request.getShopId(), merchant.getId());

        // 步骤 4: 组装商品实体（请求字段逐个拷贝）
        Product product = new Product();
        product.setShopId(request.getShopId());
        product.setCategoryId(request.getCategoryId());
        product.setProductName(request.getProductName());
        product.setDescription(request.getDescription());
        product.setCoverImage(request.getCoverImage());
        product.setImages(request.getImages());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        // 新商品：上架、销量 0
        product.setStatus(1);
        product.setSales(0);
        // 步骤 5: 插入 MySQL（MyBatis-Plus 自动回填雪花 ID）
        productMapper.insert(product);
        // 步骤 6: 双写同步 ES（事务提交后才执行）
        syncProductAfterCommit(product.getId());
        return toVO(product);
    }

    /**
     * 更新商品信息 - 部分字段更新（PATCH 语义）。
     *
     * <h3>业务场景</h3>
     * 商家改商品：换个主图、调一下价格、补充描述……
     * 只传想改的字段，其他字段保持原样。
     *
     * <h3>执行步骤</h3>
     * <ol>
     *   <li><b>校验商家身份 + 商品归属</b>：requireOwnedProduct 内部会
     *       查出商品，再校验"商品所在店铺属于当前商家"</li>
     *   <li><b>部分更新</b>：每个字段先判空再赋值，未传字段保持原值</li>
     *   <li><b>更新 MySQL</b></li>
     *   <li><b>清秒杀缓存</b>：改了价格/图，秒杀活动的展示信息要重新加载</li>
     *   <li><b>失效热门缓存 + 详情缓存 + 同步 ES</b>：三方联动保一致</li>
     * </ol>
     *
     * <h3>判空方式为什么不一样？（细节，值得注意）</h3>
     * <ul>
     *   <li>productName 用 {@code StringUtils.hasText}：它是必填字段
     *       （@NotBlank），空串/纯空格都是非法输入，直接忽略不更新；</li>
     *   <li>description/coverImage/images 用 {@code != null}：
     *       商家可能想"主动清空描述"——传空字符串 "" 表示清空，
     *       传 null（不传这个字段）表示不修改，两种语义都要能表达；</li>
     *   <li>categoryId/price/originalPrice 用 {@code != null}：
     *       数值/对象类型没有"空白"概念，判 null 即可。</li>
     * </ul>
     *
     * <h3>@CacheEvict 与手写 delete 的区别？</h3>
     * 详情缓存（productDetail）是 Spring Cache 管理的，用注解失效；
     * 秒杀信息缓存（citygo:seckill:info:）是其他模块手写的 String key，
     * 不在 Spring Cache 的命名空间里，只能用 stringRedisTemplate.delete 手动删。
     *
     * @param id            商品 ID
     * @param request       更新请求体（未传字段保持原值）
     * @param currentUserId 当前登录用户 ID
     * @return 更新后的商品视图对象
     * @throws BizException MERCHANT_NOT_FOUND / PRODUCT_NOT_FOUND /
     *                      UNAUTHORIZED_OPERATION（越权）/ CATEGORY_NOT_FOUND（换的分类不存在）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    // 商品信息修改后，失效该商品详情缓存（与 getPublicDetail 的 @Cacheable 联动）；
    // 同时失效热门商品手写缓存（商品名/图等信息变化会影响热门列表展示）。
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public ProductVO update(Long id, ProductUpdateRequest request, Long currentUserId) {
        // 步骤 1: 身份 + 归属双重校验
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());

        // 步骤 2: 部分更新——只更新"传了值"的字段，未传的保持原样
        // （直接 set 会把没传的字段覆盖成 null，造成数据丢失，这是新手最容易踩的坑）
        if (request.getCategoryId() != null) {
            // 换分类要先校验新分类存在
            if (categoryMapper.selectById(request.getCategoryId()) == null) {
                throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            product.setCategoryId(request.getCategoryId());
        }
        if (StringUtils.hasText(request.getProductName())) {
            product.setProductName(request.getProductName());
        }
        if (request.getDescription() != null) {
            product.setDescription(request.getDescription());
        }
        if (request.getCoverImage() != null) {
            product.setCoverImage(request.getCoverImage());
        }
        if (request.getImages() != null) {
            product.setImages(request.getImages());
        }
        if (request.getPrice() != null) {
            product.setPrice(request.getPrice());
        }
        if (request.getOriginalPrice() != null) {
            product.setOriginalPrice(request.getOriginalPrice());
        }

        // 步骤 3: 更新 MySQL
        productMapper.updateById(product);

        // 步骤 4: 清秒杀活动信息缓存（价格/图变了，秒杀展示要重新加载）
        stringRedisTemplate.delete("citygo:seckill:info:" + id);
        // 步骤 5: 失效热门商品手写缓存（详情缓存已由 @CacheEvict 处理）
        evictHotProductsCache();
        // 步骤 6: 事务提交后同步 ES
        syncProductAfterCommit(id);
        return toVO(product);
    }

    /**
     * 上架 / 下架商品。
     *
     * <h3>业务场景</h3>
     * 商品卖完了临时下架、或者补货后重新上架。
     * 单独做一个接口而不是走 update，是因为上下架是高频轻量操作，
     * 不应该强迫前端把整个表单字段都传一遍。
     *
     * <h3>下架后 ES 里还有这条商品吗？</h3>
     * 有，但 status=0。搜索接口会过滤掉 status=0 的文档，所以用户搜不到。
     * 保留而不是删除的好处：重新上架时不用把整条数据再同步一遍，
     * 且历史搜索统计不受影响。
     *
     * @param id            商品 ID
     * @param request       状态请求体（status：1=上架, 0=下架）
     * @param currentUserId 当前登录用户 ID
     * @throws BizException MERCHANT_NOT_FOUND / PRODUCT_NOT_FOUND / UNAUTHORIZED_OPERATION
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public void updateStatus(Long id, ProductStatusRequest request, Long currentUserId) {
        // 步骤 1: 身份 + 归属校验（与 create 相同的两级校验：userId→merchantId 翻译 + 店铺归属比对）
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        // 步骤 2: 只改 status 这一个字段。
        // 这里复用"刚从库里查出来的完整实体"再 set 一个字段，而不是 new 一个只带 id 的空实体——
        // MyBatis-Plus 的 updateById 默认只更新非 null 字段，"查出→改→写回"的模式最不容易误覆盖。
        product.setStatus(request.getStatus());
        productMapper.updateById(product);
        // 步骤 3: 缓存联动（三方数据一致性，见类注释"三方数据联动"）。
        // 3a. 秒杀信息缓存是其他模块手写的 String key，不在 Spring Cache 命名空间，只能手动 delete；
        //     下架商品不能参加秒杀，必须清掉，否则用户还能看到已下架商品的秒杀信息。
        stringRedisTemplate.delete("citygo:seckill:info:" + id);
        // 3b. 热门缓存同理手动删（手写缓存没有注解可用，"失效"= delete 那个 key）。
        //     上架/下架会改变热门榜单的范围（只推 status=1 的商品）。
        evictHotProductsCache();
        // 3c. 详情缓存不用手删——本方法上的 @CacheEvict 已在方法成功返回后自动删除 productDetail::{id}。
        //     时机差异值得注意：手动删除发生在方法体内（事务提交前），
        //     注解失效发生在方法返回后；若事务回滚，手动删的缓存只是"多删了一次"，下次访问回填即可，无害。
        // 步骤 4: 同步 ES。下架商品在 ES 里保留文档但 status=0（搜索接口过滤），
        // 保留而非删除的好处：重新上架时无需重新灌数据，历史统计不受影响——故"下架"也仍然同步。
        syncProductAfterCommit(id);
    }

    /**
     * 修改商品库存（商家手动设置库存数）。
     *
     * <h3>业务场景</h3>
     * 商家盘点后发现线上库存和实际不符，手动纠正为准确数字。
     * 注意这是"直接设置"而不是"增量加减"；
     * 下单扣库存走的是另一条链路（ProductMapper.deductStock 的 CAS 扣减），
     * 那个方法绕过本 Service，所以那边扣完要主动调 evictProductDetail 失效缓存。
     *
     * <h3>为什么库存变化要失效两种缓存？</h3>
     * 详情缓存里有 stock（商家侧接口返回），热门列表也展示库存相关信息，
     * 数据变了它们都必须失效，否则读到旧库存会误导商家。
     *
     * @param id            商品 ID
     * @param request       库存请求体（stock：新的库存数）
     * @param currentUserId 当前登录用户 ID
     * @throws BizException MERCHANT_NOT_FOUND / PRODUCT_NOT_FOUND / UNAUTHORIZED_OPERATION
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public void updateStock(Long id, ProductStockRequest request, Long currentUserId) {
        // 步骤 1: 身份 + 归属校验（同上两级校验，不再赘述）
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        // 步骤 2: 把库存"覆盖"为商家传入的新值——这是"设置"语义（盘点纠偏），不是"加减"语义。
        // 下单扣库存走的是另一条链路：ProductMapper.deductStock 的 CAS 增量扣减（UPDATE ... WHERE stock >= n），
        // 两条链路互不干扰；也正因那条链路绕过了本 Service，那边扣完要主动调 evictProductDetail 失效缓存。
        product.setStock(request.getStock());
        productMapper.updateById(product);
        // 步骤 3: 缓存联动——库存变了，热门缓存（可能展示库存相关）手动失效；
        // 详情缓存由方法上的 @CacheEvict 自动失效（商家侧"我的商品"返回真实库存，必须保证新鲜）。
        evictHotProductsCache();
        // 步骤 4: 事务提交后同步 ES，保证搜索侧与主库一致。
        syncProductAfterCommit(id);
    }

    /**
     * 当前商家的商品分页列表（商家后台"我的商品"）。
     *
     * <h3>业务场景</h3>
     * 商家在后台查看、管理自己所有店铺的商品，可按店铺筛选、按名称搜索。
     *
     * <h3>数据隔离设计（重点）</h3>
     * <ul>
     *   <li>传了 shopId：先 requireOwnedShop 校验店铺属于当前商家，再按店铺过滤；</li>
     *   <li>没传 shopId：查出当前商家名下<b>全部店铺 ID</b>，用 IN 限定范围——
     *       保证商家永远只能看到自己的商品，从查询条件层面杜绝越权；</li>
     *   <li>商家一个店铺都没有：直接返回空页，连商品表都不用查（快速返回）。</li>
     * </ul>
     *
     * <h3>与 pagePublic 的区别</h3>
     * 本方法返回真实库存（商家自己需要知道），
     * pagePublic 把 stock 置 null（对用户脱敏）；本方法能看下架商品，pagePublic 只查上架。
     *
     * @param query         查询参数（shopId/keyword/pageNum/pageSize）
     * @param currentUserId 当前登录用户 ID
     * @return 分页结果（含真实库存，供商家管理）
     */
    @Override
    public PageVO<ProductVO> pageMy(ProductMyPageQuery query, Long currentUserId) {
        // ================================================================
        // 步骤 1：身份校验 —— 确认当前用户是商家
        // ================================================================
        // requireMerchant 内部会查 merchant 表，不存在则抛 MERCHANT_NOT_FOUND，
        // 保证后续操作都有合法的商家身份
        Merchant merchant = requireMerchant(currentUserId);
        Long merchantId = merchant.getId();

        // ================================================================
        // 步骤 2：从 DTO 中提取查询参数
        // ================================================================
        // 为什么不在方法签名里直接散开参数？因为 Controller 层用 @ModelAttribute
        // 自动绑定 query string 到 DTO，Service 层用 DTO 收口更整洁（详见 ProductMyPageQuery）
        Long shopId = query.getShopId();
        String keyword = query.getKeyword();
        long pageNum = query.getPageNum();
        long pageSize = query.getPageSize();

        // ================================================================
        // 步骤 3：构建查询条件（分两条路径：指定店铺 vs 全部店铺）
        // ================================================================
        // LambdaQueryWrapper 是 MyBatis-Plus 的类型安全查询构造器，
        // 用方法引用（如 Product::getShopId）代替手写字段名，编译期就能发现拼写错误
        var qw = Wrappers.<Product>lambdaQuery();

        if (shopId != null) {
            // 路径 A：指定了店铺 ID
            // 先校验店铺归属（requireOwnedShop 会查 shop 表，确认 merchantId 匹配，
            // 不匹配则抛 403 FORBIDDEN）—— 防止商家通过篡改 shopId 参数偷看别人的商品
            requireOwnedShop(shopId, merchantId);
            // 校验通过后，只查这个店铺下的商品
            qw.eq(Product::getShopId, shopId);
        } else {
            // 路径 B：未指定店铺 —— 查该商家名下所有店铺的商品
            // 先查出该商家拥有的全部店铺 ID 列表
            List<Long> myShopIds = shopMapper.selectList(
                            Wrappers.<Shop>lambdaQuery().eq(Shop::getMerchantId, merchantId))
                    .stream()
                    .map(Shop::getId)
                    .toList();

            if (myShopIds.isEmpty()) {
                // 商家名下没有任何店铺 → 不可能有商品，直接返回空页
                // 省掉一次无意义的商品表查询（快速返回，提升性能）
                PageVO<ProductVO> empty = new PageVO<>();
                empty.setRecords(List.of());
                empty.setTotal(0);
                empty.setPageNum(pageNum);
                empty.setPageSize(pageSize);
                return empty;
            }

            // 用 IN 条件限定范围：WHERE shop_id IN (1, 3, 5)
            // 这样即使商家有很多店铺，也只发一条 SQL，不会逐个店铺查
            qw.in(Product::getShopId, myShopIds);
        }

        // ================================================================
        // 步骤 4：追加可选条件 —— 关键词模糊搜索
        // ================================================================
        // StringUtils.hasText 比 StringUtils.isNotEmpty 更严格：
        // 不仅 null 和 "" 会跳过，纯空格字符串（如 "   "）也会跳过
        if (StringUtils.hasText(keyword)) {
            // LIKE 模糊匹配：WHERE product_name LIKE '%keyword%'
            // 注意：MyBatis-Plus 的 like 方法会自动处理 SQL 注入（用预编译占位符 ?），
            // 不会把用户输入直接拼进 SQL 字符串
            qw.like(Product::getProductName, keyword);
        }

        // ================================================================
        // 步骤 5：排序 —— 按 ID 倒序（最新创建的商品排前面）
        // ================================================================
        // 雪花算法生成的 ID 是单调递增的，所以按 ID 倒序 ≈ 按创建时间倒序
        // 不需要额外查 create_time 字段，索引效率更高
        qw.orderByDesc(Product::getId);

        // ================================================================
        // 步骤 6：执行分页查询
        // ================================================================
        // buildPage 方法会对 pageSize 做夹逼（封顶 50），防止恶意大值拉全表
        // MyBatis-Plus 分页插件会自动拦截，追加 LIMIT 和 COUNT 查询
        Page<Product> page = productMapper.selectPage(buildPage(pageNum, pageSize), qw);

        // ================================================================
        // 步骤 7：组装 PageVO（把 MP 内部 Page 对象转成对外稳定的 PageVO）
        // ================================================================
        // 为什么不直接返回 Page<Product>？因为 Page 是 MP 内部实现，
        // 暴露给前端会把 searchCount/optimizeCountSql 等内部字段也带出去，
        // 且前后端契约不稳定（MP 升级可能改字段）
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());       // 符合条件的总条数（MP 自动执行的 COUNT 结果）
        pageVO.setPageNum(page.getCurrent());   // 当前页码
        pageVO.setPageSize(page.getSize());     // 每页条数

        // ================================================================
        // 步骤 8：批量查分类名（解决 N+1 问题）
        // ================================================================
        // 如果逐条商品查分类（每条商品 selectById(categoryId)），
        // 20 条商品 = 20 次分类查询，加上商品列表查询总共 1+20=21 次 SQL
        // loadCategoryNames 先收集所有去重的 categoryId，一次 selectByIds 取回，
        // 转成 Map<分类ID, 分类名>，之后组装 VO 时直接 get 查表 —— 总共只查 2 次
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());

        // ================================================================
        // 步骤 9：实体转 VO + 商家侧不脱敏
        // ================================================================
        // 商家后台需要看到真实库存做管理，所以走 toVO（不脱敏），
        // 而公开接口 pagePublic 走 toPublicVO（stock 置 null）
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> toVO(p, categoryIdNameMap))
                .toList());

        return pageVO;
    }

    /**
     * 公开商品分页列表（用户端，无需登录）。
     *
     * <h3>业务场景</h3>
     * 用户在首页/分类页/店铺页浏览商品，支持组合筛选：
     * 按店铺、按分类、关键词、价格区间，外加四种排序。
     *
     * <h3>安全设计三件套（重点学习）</h3>
     * <ol>
     *   <li><b>只查上架</b>：status=1 硬编码在条件里，
     *       用户永远看不到商家下架的商品；</li>
     *   <li><b>排序白名单</b>：sort 参数不直接拼进 SQL，
     *       而是 switch 映射到固定的查询方法——用户传任何非法值
     *       都只会落入 default 分支。如果直接拼接，就是 SQL 注入漏洞；</li>
     *   <li><b>分页封顶</b>：pageSize 最大 50（Math.min 夹逼），
     *       防止有人传 pageSize=999999 拖垮数据库。</li>
     * </ol>
     *
     * <h3>库存脱敏</h3>
     * 返回前把每条记录的 stock 置 null——库存是商家经营机密，
     * 用户只需要"能不能买"（下单接口会真正校验库存），不需要"还剩几件"。
     *
     * @param query 查询参数对象（Spring 自动绑定 URL 上的 query string），
     *              全部字段可选，pageNum 默认 1、pageSize 默认 10
     * @return 分页结果（stock 已置 null 脱敏）
     * @throws BizException SHOP_NOT_FOUND（指定店铺不存在或已被平台禁用）
     */
    @Override
    public PageVO<ProductVO> pagePublic(ProductBrowseQuery query) {
        Long shopId = query.getShopId();
        // 指定店铺时校验店铺存在且正常营业（被平台封禁的店铺商品不应展示）
        if (shopId != null) {
            Shop shop = shopMapper.selectById(shopId);
            if (shop == null || shop.getStatus() == 0) {
                throw new BizException(ErrorCode.SHOP_NOT_FOUND);
            }
        }

        var qw = Wrappers.<Product>lambdaQuery();
        // LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        // 只查上架商品（用户端永远看不到 status=0 的商品）
        qw.eq(Product::getStatus, 1);
        // 以下筛选条件全部"可选"：传了才生效，不传不加条件
        if (shopId != null) {
            qw.eq(Product::getShopId, shopId);
        }
        if (query.getCategoryId() != null) {
            qw.eq(Product::getCategoryId, query.getCategoryId());
        }
        if (StringUtils.hasText(query.getKeyword())) {
            qw.like(Product::getProductName, query.getKeyword());
        }
        if (query.getMinPrice() != null) {
            qw.ge(Product::getPrice, query.getMinPrice());   // price >= minPrice
        }
        if (query.getMaxPrice() != null) {
            qw.le(Product::getPrice, query.getMaxPrice());   // price <= maxPrice
        }
        // 排序白名单映射：排序字段必须白名单映射，防止 SQL 注入。
        // 每个 case 都追加 orderByDesc(id) 作为"第二排序键"：
        // 价格相同的商品按 ID 倒序，保证分页结果稳定（否则翻页时可能出现重复/漏项）
        String sortKey = query.getSort() == null ? "default" : query.getSort();
        switch (sortKey) {
            case "sales" -> qw.orderByDesc(Product::getSales).orderByDesc(Product::getId);
            case "price_asc" -> qw.orderByAsc(Product::getPrice).orderByDesc(Product::getId);
            case "price_desc" -> qw.orderByDesc(Product::getPrice).orderByDesc(Product::getId);
            default -> qw.orderByDesc(Product::getId);  // 非法值也走这里，天然免疫注入
        }

        // 分页参数夹逼收口在 buildPage：pageSize 限制在 [1, 50]，pageNum 下界 1（防负 offset 非法 SQL）
        Page<Product> page = productMapper.selectPage(buildPage(query.getPageNum(), query.getPageSize()), qw);
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        // 公开端统一走 toPublicVO：实体转 VO + 库存脱敏收口一处
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> toPublicVO(p, categoryIdNameMap))
                .toList());
        return pageVO;
    }

    /**
     * 公开商品详情（用户端，无需登录）。
     *
     * <h3>@Cacheable 在这里怎么工作？</h3>
     * 详情是典型的"读多写少"数据（一个商品可能被几千人查看），
     * 所以用 Spring Cache 缓存：第一次调用查库并把结果存进 Redis
     * （key = productDetail::{id}），之后 10 分钟内（TTL 见 RedisCacheConfig）
     * 再查同一商品直接返回缓存，不碰数据库。
     * 商家修改商品时，update/updateStatus/updateStock 上的
     * @CacheEvict 会立即删掉缓存，下次访问重新回填——这就是 Cache-Aside 模式。
     *
     * <h3>为什么"下架"抛 404 而不是"已售罄"之类的提示？</h3>
     * 对不存在和已下架返回同一个错误码（PRODUCT_NOT_FOUND），
     * 不向外界泄露"这个商品曾经存在过"的信息，这是接口设计的常见做法。
     *
     * @param id 商品 ID
     * @return 商品详情（stock 已置 null 脱敏）
     * @throws BizException PRODUCT_NOT_FOUND（不存在或已下架）
     */
    @Override
    @Cacheable(cacheNames = "productDetail", key = "#id")
    public ProductVO getPublicDetail(Long id) {
        // 方法体只在缓存未命中时才会执行（命中则由 Spring Cache 切面直接返回缓存值）。
        Product product = productMapper.selectById(id);
        // 不存在或非上架状态都抛同一个错误码：不向外界泄露"这个商品曾经存在过"的信息。
        // 用 Integer.valueOf(1).equals 判等而非 == 拆箱，防御 status 为 null 时 NPE
        // （与 AuthServiceImpl.login 同一防御约定：数据库虽 NOT NULL，实体层不作假设）。
        if (product == null || !Integer.valueOf(1).equals(product.getStatus())) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        // 组装分类名（单条场景，Map 只有一个条目）后转 VO，并按公开端规则统一脱敏。
        // 注意：抛异常的方法不会有缓存副作用——Spring Cache 不会把异常结果缓存下来。
        return toPublicVO(product, loadCategoryNames(List.of(product)));
    }

    /**
     * 热门商品（销量 top N 上架商品），手写「缓存三防」。
     *
     * <p>这里不用 @Cacheable 而手写缓存，是因为要完整展示三个经典问题与对策：</p>
     * <ul>
     *   <li><b>缓存穿透</b>：大量请求查询"不存在"的数据，直接打穿缓存打到 DB。
     *       对策：查库结果为空时也缓存一个「空值标记」（短 TTL），下次命中直接返回空，不再打 DB；</li>
     *   <li><b>缓存击穿</b>：热点 key 过期瞬间，海量并发同时回源查 DB。
     *       对策：用互斥锁保证同一时刻只有一个线程重建缓存，其余等待后二次查缓存（double-check）；</li>
     *   <li><b>缓存雪崩</b>：大量 key 同一时刻集中过期，DB 瞬时压力激增。
     *       对策：给每个 key 的 TTL 叠加一个随机偏移，把过期时间打散。</li>
     * </ul>
     *
     * <h3>完整流程图</h3>
     * <pre>
     * 请求 → 查缓存 ──命中──→ 直接返回
     *           │
     *         未命中
     *           │
     *        抢互斥锁 ──抢到──→ double-check 缓存
     *           │                  │
     *           │              仍无 → 查库 → 回写缓存（空则写空标记）→ 返回
     *        没抢到
     *           │
     *     睡 100ms 重试读缓存 ×3
     *           │
     *       仍无 → 降级直接查库返回（不写缓存，把重建机会留给持锁线程）
     * </pre>
     *
     * <h3>缓存权威长度与按请求切片</h3>
     * 缓存 key 不含 limit。若重建时按"第一个请求的 limit"灌缓存，后续更大的 limit
     * 请求会命中"小缓存"而少返回，limit 契约被悄悄破坏。对策：缓存始终重建为
     * 固定的权威长度（top {@link #MAX_HOT_LIMIT}），每个请求按夹逼后的 limit 切片返回
     * ——一个 key 服务所有 limit 取值，单次回源查库的规模也恒定可控。
     *
     * @param limit 返回条数（自动夹逼到 [1, {@link #MAX_HOT_LIMIT}]）
     * @return 热门商品列表（stock 已脱敏，条数 = min(limit, 实际命中数)）
     */
    @Override
    public List<ProductVO> getHotProducts(int limit) {
        // 步骤 0: 入参夹逼——limit 限制在 [1, MAX_HOT_LIMIT]。
        // 只防下界防不住恶意大值：limit=100000 会把全表拉进内存再序列化塞进 Redis，
        // 与列表接口 pageSize 的夹逼（buildPage）是同一防御思想。
        int safeLimit = Math.min(Math.max(limit, 1), MAX_HOT_LIMIT);

        // a. 先查缓存（解析失败会降级为 null，视同未命中）
        List<ProductVO> cached = readHotCacheFromRedis();
        if (cached != null) {
            return sliceHot(cached, safeLimit);
        }

        // b. 未命中：抢互斥锁，抢到者负责重建缓存（防击穿）。
        //    token 是本次加锁的"凭证"，释放锁时校验凭证，防止误删别人的锁
        String token = UUID.randomUUID().toString();
        if (redisLockUtil.tryLock(HOT_PRODUCTS_LOCK_KEY, 3, token)) {
            try {
                // double-check：抢锁期间可能已有其他线程把缓存回填好了，再查一次避免重复回源
                List<ProductVO> cached2 = readHotCacheFromRedis();
                if (cached2 != null) {
                    return sliceHot(cached2, safeLimit);
                }
                // 缓存确实没有 → 查库。固定查权威长度 MAX_HOT_LIMIT 而不是 safeLimit：
                // 缓存是所有请求共享的，按请求 limit 灌缓存会让后续大 limit 请求读到"残缺缓存"
                List<ProductVO> hot = queryHotFromDb(MAX_HOT_LIMIT);
                if (hot.isEmpty()) {
                    // 结果为空：写空值标记（短 TTL 30s），防止穿透
                    stringRedisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, EMPTY_MARKER, EMPTY_TTL);
                } else {
                    // 非空：写 JSON 缓存，TTL = 5 分钟 + 随机 0~60 秒（防雪崩）。
                    // 序列化失败不阻断主流程，仅记日志并直接返回查库结果（本次不缓存）
                    try {
                        stringRedisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, toJson(hot), randomTtl());
                    } catch (Exception e) {
                        log.error("热门商品缓存写入失败（降级为直接返回查库结果）: ", e);
                    }
                }
                return sliceHot(hot, safeLimit);
            } finally {
                // 无论成败都释放锁（Lua 原子释放，只删自己的锁）
                redisLockUtil.unlock(HOT_PRODUCTS_LOCK_KEY, token);
            }
        }

        // c. 抢锁失败：说明他人正在重建，短暂休眠后重试几次（等它写好）
        for (int i = 0; i < 3; i++) {
            sleep(100);
            List<ProductVO> c = readHotCacheFromRedis();
            if (c != null) {
                return sliceHot(c, safeLimit);
            }
        }
        // 重试仍无缓存：降级直接查库返回（不写缓存，查多少返回多少，把重建机会留给持锁线程）
        return queryHotFromDb(safeLimit);
    }

    /**
     * 秒杀商品列表：已配置秒杀价且上架的商品，按秒杀开始时间升序。
     *
     * <p>与热门商品不同，这里不做缓存：秒杀的真实库存在 Redis（citygo:seckill:stock:*），
     * DB 的 seckill_stock 仅在取消回补等低频场景变化；而秒杀配置（商家改价/改时间）
     * 需要近实时生效，缓存反而引入不一致。单次 isNotNull + LIMIT 50 的查询成本很低。</p>
     */
    @Override
    public List<ProductVO> getSeckillProducts() {
        var qw = Wrappers.<Product>lambdaQuery();
        // 已配置秒杀（秒杀价非空）且上架中；未开始/进行中/已结束由前端按当前时间分桶展示
        qw.isNotNull(Product::getSeckillPrice)
                .eq(Product::getStatus, 1)
                .orderByAsc(Product::getSeckillStart)
                .last("LIMIT 50");
        // 秒杀页不展示分类名，传空 Map 复用统一转换（含公开端库存脱敏收口）
        return productMapper.selectList(qw).stream()
                .map(p -> toPublicVO(p, Map.of()))
                .toList();
    }

    /**
     * 失效指定商品详情缓存（供 OrderServiceImpl 下单扣库存等旁路写操作调用）。
     *
     * <p>下单扣库存走 {@link ProductMapper#deductStock}，绕过了 ProductService，
     * 因此这里提供一个带 @CacheEvict 的入口，由下单成功方主动调用，保证写后缓存一致。</p>
     *
     * <p><b>空方法体不是 bug！</b>方法的"实现"就是注解本身：
     * 调用经过 Spring 代理时，@CacheEvict 切面会在方法执行后删除
     * productDetail::{productId} 缓存。这是把"缓存失效"封装成语义化接口的常用技巧。</p>
     *
     * @param productId 商品 ID
     */
    @Override
    @CacheEvict(cacheNames = "productDetail", key = "#productId")
    public void evictProductDetail(Long productId) {
        // 方法体为空：仅靠 @CacheEvict 注解失效 productDetail::{productId}
    }

    /**
     * 失效热门商品缓存（供下单/上下架等写操作调用）。
     *
     * <p>注意：热门商品是手写 StringRedisTemplate 缓存（key 自定义），与 Spring Cache 的
     * 存储是两套 key 空间，因此不能用 @CacheEvict，而必须手动 delete 同一个 key。</p>
     *
     * <p>典型调用时机：下单成功后销量变了（热门按销量排序），
     * 榜单需要重新计算，所以直接删缓存，下次有人访问时重建。</p>
     */
    @Override
    public void evictHotProducts() {
        evictHotProductsCache();
    }

    // ============================================================
    // 私有辅助方法 - 内部复用的校验/缓存/转换逻辑
    // ============================================================

    /**
     * 构造规范化分页参数：pageNum/pageSize 夹逼到安全范围。
     *
     * <p>pageNum 下界 1（MyBatis-Plus 的 offset = (current-1)*size，current &lt; 1 会产生
     * 负数 offset 交给 MySQL 报 SQL 错误）；pageSize 限制在 [1, {@link #MAX_PAGE_SIZE}]，
     * 防止一次拉取过多数据。两个列表查询（pageMy / pagePublic）共用此方法收口，
     * 保证分页语义一致；新增列表接口也应走这里，避免夹逼逻辑再散落。</p>
     *
     * <p>设计取舍：这里选择「静默夹逼」而非 Bean Validation 硬拒绝（@Min/@Max 返回 400）——
     * 对浏览类接口，用户传 0 也能正常看到第一页，体验更友好。</p>
     *
     * @param pageNum  页码（从 1 开始，小于 1 视为 1）
     * @param pageSize 每页条数（限制在 [1, 50]）
     * @return 规范化后的 MP 分页对象
     */
    private Page<Product> buildPage(long pageNum, long pageSize) {
        return new Page<>(Math.max(pageNum, 1),
                Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE));
    }

    /**
     * 按当前登录用户 ID 解析其商家身份；非商家抛异常。
     *
     * <p>商家账号与普通用户同源（user 表），merchant 表通过 user_id 关联。
     * 查不到 merchant 记录说明当前用户不是商家，无权操作商品。</p>
     *
     * @param currentUserId 当前登录用户 ID
     * @return 商家实体（保证非 null）
     * @throws BizException MERCHANT_NOT_FOUND
     */
    private Merchant requireMerchant(Long currentUserId) {
        Merchant merchant = merchantService.getByUserId(currentUserId);
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    /**
     * 校验店铺归属：店铺必须存在且属于当前商家，否则抛 403/404。
     *
     * <p>注意这里是"两步合一"：先查店铺属于谁（getMerchantIdOfShop），
     * 查不到 → 店铺不存在（404）；查到但不是你 → 越权（403）。
     * 归属查询复用了 ShopService 的方法，避免在本类重复注入店铺表逻辑。</p>
     *
     * @param shopId     店铺 ID
     * @param merchantId 当前商家的 ID
     * @throws BizException SHOP_NOT_FOUND（店铺不存在）/ UNAUTHORIZED_OPERATION（不是你的店铺）
     */
    private void requireOwnedShop(Long shopId, Long merchantId) {
        Long owner = shopService.getMerchantIdOfShop(shopId);
        if (owner == null) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        if (!owner.equals(merchantId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
    }

    /**
     * 查询商品并做归属校验：不存在抛 404，非自己店铺的商品抛 403。
     *
     * <p>这是"两级校验"的中间层：商品 → 店铺 → 商家。
     * 先确认商品存在，再借 requireOwnedShop 确认商品所在店铺属于当前商家。
     * update/updateStatus/updateStock 三个写操作都从这里进，校验逻辑只写一遍。</p>
     *
     * @param productId  商品 ID
     * @param merchantId 当前商家的 ID
     * @return 通过校验的商品实体（保证非 null 且归属正确）
     * @throws BizException PRODUCT_NOT_FOUND / SHOP_NOT_FOUND / UNAUTHORIZED_OPERATION
     */
    private Product requireOwnedProduct(Long productId, Long merchantId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        requireOwnedShop(product.getShopId(), merchantId);
        return product;
    }

    /**
     * 事务提交后把商品最新数据同步到 ES（主库为准，失败仅记 ERROR）。
     *
     * <p>为什么要"事务提交后"？如果在事务内同步，事务回滚后
     * ES 里会留下"幽灵文档"。SearchSync.afterCommit 利用 Spring 事务同步器，
     * 保证只有 MySQL 真正 commit 成功才写 ES。详见 {@link SearchSync}。</p>
     *
     * <p>回调里重新 selectById 而不是复用内存对象：
     * 拿到数据库回填的最新完整数据（如 create_time），保证 ES 文档与库一致。</p>
     *
     * @param productId 商品 ID
     */
    private void syncProductAfterCommit(Long productId) {
        searchSync.afterCommit(() -> productSearchService.syncProduct(productMapper.selectById(productId)));
    }

    /**
     * 手动删除热门商品缓存 key（手写缓存的失效方式）。
     *
     * <p>手写缓存没有注解可用，"失效"= 直接 delete 那个 key。
     * 下次读请求发现缓存没了，自然走重建逻辑。</p>
     */
    private void evictHotProductsCache() {
        stringRedisTemplate.delete(HOT_PRODUCTS_KEY);
    }

    /**
     * 从库里取销量 top N 的上架商品并脱敏（getHotProducts 的回源查询）。
     *
     * <p>两个小细节：
     * <ul>
     *   <li>new Page&lt;&gt;(1, limit, false) 的第三个参数 searchCount=false：
     *       告诉 MyBatis-Plus 别执行 COUNT(*) 总数查询——我们只要前 N 条，
     *       不需要总页数，省一次全表统计；</li>
     *   <li>按 sales 排序时追加 id 倒序做第二排序键，销量相同时顺序稳定。</li>
     * </ul>
     * </p>
     *
     * @param limit 取前 N 条
     * @return 热门商品列表（stock 已置 null）
     */
    private List<ProductVO> queryHotFromDb(int limit) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        qw.eq(Product::getStatus, 1);  // 只推上架商品
        qw.orderByDesc(Product::getSales).orderByDesc(Product::getId);
        // 用物理分页取前 limit 条（searchCount=false 省掉 count 查询）
        Page<Product> page = productMapper.selectPage(new Page<>(1, Math.max(limit, 1), false), qw);
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        // 公开端统一走 toPublicVO：实体转 VO + 库存脱敏收口一处
        return page.getRecords().stream()
                .map(p -> toPublicVO(p, categoryIdNameMap))
                .toList();
    }

    /** 读取热门缓存 = Redis 取串 + 解析（组合动作，本类三处调用收口于此）。 */
    private List<ProductVO> readHotCacheFromRedis() {
        return readHotCache(stringRedisTemplate.opsForValue().get(HOT_PRODUCTS_KEY));
    }

    /**
     * 按请求 limit 切片：缓存存的是权威长度 top N，单个请求只取前 limit 条。
     * 商品总数不足 limit 时，stream().limit 天然只返回实际存在的条数。
     */
    private List<ProductVO> sliceHot(List<ProductVO> list, int limit) {
        return list.stream().limit(limit).toList();
    }

    /**
     * 读取并解析热门商品缓存，统一处理三种情况：
     * <ul>
     *   <li>null（未命中）→ 返回 null，调用方视同未命中；</li>
     *   <li>空字符串（空值标记，防穿透）→ 返回空列表；</li>
     *   <li>JSON 解析失败（缓存数据损坏/结构变更）→ 删除坏 key 降级为未命中（返回 null），
     *       不抛异常阻断主流程，由调用方走查库逻辑。</li>
     * </ul>
     *
     * <p>体现的原则：<b>缓存是优化手段，不是依赖项</b>——
     * 缓存出任何问题，业务都应照常工作（最多多查几次库）。</p>
     *
     * @param cached Redis 里读到的原始字符串（可能为 null）
     * @return 解析后的列表；空标记返回空列表；未命中或解析失败返回 null
     */
    private List<ProductVO> readHotCache(String cached) {
        if (cached == null) {
            return null;
        }
        if (cached.isEmpty()) {
            // 命中空值标记：说明此前查库无结果（防穿透）
            return List.of();
        }
        try {
            // ================================================================
            // 反序列化：把 Redis 读出的 JSON 字符串还原成 List<ProductVO>
            // ================================================================
            // 1) 为什么不能写 jsonMapper.readValue(cached, List.class)？
            //    Java 泛型是编译期概念，运行时会【擦除】元素类型信息。
            //    只告诉 Jackson "目标是一个 List"，它并不知道元素是 ProductVO，
            //    只能把每个 JSON 对象解析成通用类型 LinkedHashMap（"乱入"的通用 Map）。
            //    编译期不报错（擦除后两边都算 List），运行期取元素时才抛
            //    ClassCastException: LinkedHashMap cannot be cast to ProductVO，
            //    属于最难排查的"编译期无恙、运行期爆炸"型坑。
            //
            // 2) new TypeReference<List<ProductVO>>(){} 的魔法在哪？
            //    花括号 {} 不是空方法体，而是在声明一个【匿名内部类】——
            //    TypeReference 的真实子类。该子类在编译时会把父类的泛型实参
            //    List<ProductVO> 烙进自己的字节码（这部分逃过了泛型擦除）。
            //    随后 TypeReference 构造器通过 getGenericSuperclass() 挖出这条
            //    完整签名，Jackson 便得知"这是一个 List，元素是 ProductVO"，
            //    从而按正确类型逐一构建 List<ProductVO> 元素。
            //
            // 3) 整体含义：cached（Redis 读到的 JSON 字符串）→ List<ProductVO>
            //    转换失败时抛 JacksonException，由下方 catch 删除坏 key 降级查库。
            // ================================================================
            return jsonMapper.readValue(cached, new TypeReference<List<ProductVO>>() {
            });
        } catch (JacksonException e) {
            // 缓存数据损坏：删掉坏 key，本次视同未命中，下次请求会重建缓存
            log.error("热门商品缓存反序列化失败，删除坏 key 降级查库: ", e);
            stringRedisTemplate.delete(HOT_PRODUCTS_KEY);
            return null;
        }
    }

    /**
     * List&lt;ProductVO&gt; 序列化为 JSON 字符串（存入 Redis）。
     *
     * <p>Redis 只能存字符串等简单类型，Java 对象列表必须先序列化成 JSON。
     * 失败时抛 RuntimeException，由调用方（getHotProducts）捕获降级。</p>
     */
    private String toJson(List<ProductVO> list) {
        try {
            return jsonMapper.writeValueAsString(list);
        } catch (JacksonException e) {
            throw new RuntimeException("热门商品缓存序列化失败", e);
        }
    }

    /**
     * 热门商品缓存 TTL：5 分钟 + 随机 0~60 秒偏移，把过期时间打散，防缓存雪崩。
     *
     * <p>如果所有 key 都是整 5 分钟过期，它们会在同一瞬间集体失效，
     * 数据库被"雪崩式"回源打爆。加随机偏移后过期时间被摊平到 5~6 分钟之间。</p>
     */
    private Duration randomTtl() {
        int offsetSeconds = ThreadLocalRandom.current().nextInt(0, 61);
        return HOT_PRODUCTS_TTL.plusSeconds(offsetSeconds);
    }

    /**
     * 休眠指定毫秒（抢锁失败后等待持锁线程重建缓存）。
     *
     * <p>InterruptedException 处理的规范写法：恢复中断标志位
     * （Thread.currentThread().interrupt()），把中断信号传递给上层，
     * 而不是吞掉——吞掉会让线程池无法正常关闭线程。</p>
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 查询商品所属分类的名称映射，避免逐条查库（N+1）。
     *
     * <p><b>什么是 N+1？</b>列表有 20 条商品，逐条查分类就是 1+20 次查询。
     * 本方法先收集去重后的分类 ID，一次 selectByIds 查完，
     * 返回 Map&lt;分类ID, 分类名&gt;，转换 VO 时直接 get——总共只查 2 次。</p>
     *
     * @param products 商品列表
     * @return 分类 ID → 分类名 的映射（入参为空时返回空 Map）
     */
    private Map<Long, String> loadCategoryNames(List<Product> products) {
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .distinct()  // 去重：20 条商品可能同属 3 个分类
                .toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
    }

    /**
     * 商品 → 视图对象（无分类名映射，用于单条场景）。
     *
     * <p>单条场景（如 create 后返回）没有现成的映射 Map，
     * 传空 Map，categoryName 为 null。</p>
     */
    private ProductVO toVO(Product product) {
        return toVO(product, Map.of());
    }

    /**
     * 商品 → 公开端 VO：转换后统一执行「库存脱敏」（stock 置 null）。
     *
     * <p>公开端三个出参场景（pagePublic 列表 / getPublicDetail 详情 / queryHotFromDb 热门）
     * 共用本方法收口，让「公开接口永不暴露库存」这条规则只写一遍；
     * 商家端（pageMy）需要真实库存做管理，不走这里。</p>
     *
     * @param product           商品实体
     * @param categoryIdNameMap 分类 ID → 名称映射
     * @return 已脱敏的商品视图对象
     */
    private ProductVO toPublicVO(Product product, Map<Long, String> categoryIdNameMap) {
        ProductVO vo = toVO(product, categoryIdNameMap);
        // 公开接口脱敏：库存是商家内部经营数据，用户只关心"能不能买"（下单时才真正校验库存）
        vo.setStock(null);
        return vo;
    }

    /**
     * 商品 → 视图对象（附带分类名映射）。
     *
     * <p><b>为什么要 Entity → VO 转换？</b>
     * <ol>
     *   <li>解耦：数据库表结构变化不影响接口返回格式；</li>
     *   <li>补充展示字段：categoryName 不在 product 表里，需要额外查询组装；</li>
     *   <li>脱敏控制：调用方拿到 VO 后可以按需清掉敏感字段（如 stock = null）。</li>
     * </ol>
     * 秒杀字段（seckill*）对商家和用户都可见（活动价本来就要展示），
     * 唯独 stock 库存做了区分处理。</p>
     *
     * @param product            商品实体
     * @param categoryIdNameMap  分类 ID → 名称映射（loadCategoryNames 的产物）
     * @return 商品视图对象
     */
    private ProductVO toVO(Product product, Map<Long, String> categoryIdNameMap) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setShopId(product.getShopId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryIdNameMap.getOrDefault(product.getCategoryId(), null));
        vo.setProductName(product.getProductName());
        vo.setDescription(product.getDescription());
        vo.setCoverImage(product.getCoverImage());
        vo.setImages(product.getImages());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setSeckillPrice(product.getSeckillPrice());
        vo.setSeckillStock(product.getSeckillStock());
        vo.setSeckillStart(product.getSeckillStart());
        vo.setSeckillEnd(product.getSeckillEnd());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }

}