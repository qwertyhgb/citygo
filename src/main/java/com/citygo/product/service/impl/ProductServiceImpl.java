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
import com.citygo.product.dto.ProductCreateRequest;
import com.citygo.product.dto.ProductStatusRequest;
import com.citygo.product.dto.ProductStockRequest;
import com.citygo.product.dto.ProductUpdateRequest;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品域服务实现。
 *
 * <p>所有商品写操作均做"店铺归属 → 商家"的两级校验：
 * 商品属于某个店铺，店铺属于当前商家，否则抛越权 403。
 * "我的商品"分页默认只返回当前商家名下店铺的商品，避免数据越权。</p>
 */
@Service
public class ProductServiceImpl implements ProductService {

    /** 热门商品缓存 key（手写缓存，见 getHotProducts） */
    private static final String HOT_PRODUCTS_KEY = "citygo:cache:hotProducts";

    /** 热门商品缓存重建互斥锁 key */
    private static final String HOT_PRODUCTS_LOCK_KEY = "citygo:lock:hotProducts";

    /** 热门商品缓存基础 TTL：5 分钟 */
    private static final Duration HOT_PRODUCTS_TTL = Duration.ofMinutes(5);

    /** 空值标记缓存 TTL：30 秒（防穿透的短缓存） */
    private static final Duration EMPTY_TTL = Duration.ofSeconds(30);

    /** 空值标记（无热门商品时缓存空字符串，区别于"未命中"的 null） */
    private static final String EMPTY_MARKER = "";

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final ShopMapper shopMapper;
    private final ShopService shopService;
    private final MerchantService merchantService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisLockUtil redisLockUtil;
    private final JsonMapper jsonMapper;

    public ProductServiceImpl(ProductMapper productMapper,
                              CategoryMapper categoryMapper,
                              ShopMapper shopMapper,
                              ShopService shopService,
                              MerchantService merchantService,
                              StringRedisTemplate stringRedisTemplate,
                              RedisLockUtil redisLockUtil,
                              JsonMapper jsonMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.shopMapper = shopMapper;
        this.shopService = shopService;
        this.merchantService = merchantService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisLockUtil = redisLockUtil;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 按当前登录用户ID解析其商家身份；非商家抛 MERCHANT_NOT_FOUND。
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

    @Override
    public ProductVO create(ProductCreateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        // 分类存在性校验
        if (categoryMapper.selectById(request.getCategoryId()) == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        // 店铺归属校验
        requireOwnedShop(request.getShopId(), merchant.getId());

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
        productMapper.insert(product);
        return toVO(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    // 商品信息修改后，失效该商品详情缓存（与 getPublicDetail 的 @Cacheable 联动）；
    // 同时失效热门商品手写缓存（商品名/图等信息变化会影响热门列表展示）。
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public ProductVO update(Long id, ProductUpdateRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        if (request.getCategoryId() != null) {
            if (categoryMapper.selectById(request.getCategoryId()) == null) {
                throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            product.setCategoryId(request.getCategoryId());
        }
        product.setProductName(request.getProductName());
        product.setDescription(request.getDescription());
        product.setCoverImage(request.getCoverImage());
        product.setImages(request.getImages());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        productMapper.updateById(product);
        evictHotProductsCache();
        return toVO(product);
    }

    @Override
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public void updateStatus(Long id, ProductStatusRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        product.setStatus(request.getStatus());
        productMapper.updateById(product);
        // 上架/下架会改变热门商品范围，失效热门缓存
        evictHotProductsCache();
    }

    @Override
    @CacheEvict(cacheNames = "productDetail", key = "#id")
    public void updateStock(Long id, ProductStockRequest request, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Product product = requireOwnedProduct(id, merchant.getId());
        product.setStock(request.getStock());
        productMapper.updateById(product);
        // 商品数据变化，失效详情与热门缓存（保持写后一致性）
        evictHotProductsCache();
    }

    @Override
    public PageVO<ProductVO> pageMy(Long shopId, String keyword, long pageNum, long pageSize, Long currentUserId) {
        Merchant merchant = requireMerchant(currentUserId);
        Long merchantId = merchant.getId();

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        if (shopId != null) {
            requireOwnedShop(shopId, merchantId);
            qw.eq(Product::getShopId, shopId);
        } else {
            // 未指定店铺时，限定为当前商家名下所有店铺的商品
            List<Long> myShopIds = shopMapper.selectList(
                            Wrappers.<Shop>lambdaQuery().eq(Shop::getMerchantId, merchantId))
                    .stream()
                    .map(Shop::getId)
                    .toList();
            if (myShopIds.isEmpty()) {
                PageVO<ProductVO> empty = new PageVO<>();
                empty.setRecords(List.of());
                empty.setTotal(0);
                empty.setPageNum(pageNum);
                empty.setPageSize(pageSize);
                return empty;
            }
            qw.in(Product::getShopId, myShopIds);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Product::getProductName, keyword);
        }
        qw.orderByDesc(Product::getId);

        Page<Product> page = productMapper.selectPage(new Page<>(pageNum, pageSize), qw);
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> toVO(p, categoryIdNameMap))
                .toList());
        return pageVO;
    }

    @Override
    public PageVO<ProductVO> pagePublic(Long shopId, Long categoryId, String keyword,
                                        java.math.BigDecimal minPrice, java.math.BigDecimal maxPrice,
                                        String sort, long pageNum, long pageSize) {
        // 指定店铺时校验店铺存在且正常营业
        if (shopId != null) {
            Shop shop = shopMapper.selectById(shopId);
            if (shop == null || shop.getStatus() == 0) {
                throw new BizException(ErrorCode.SHOP_NOT_FOUND);
            }
        }

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        // 只查上架商品
        qw.eq(Product::getStatus, 1);
        if (shopId != null) {
            qw.eq(Product::getShopId, shopId);
        }
        if (categoryId != null) {
            qw.eq(Product::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Product::getProductName, keyword);
        }
        if (minPrice != null) {
            qw.ge(Product::getPrice, minPrice);
        }
        if (maxPrice != null) {
            qw.le(Product::getPrice, maxPrice);
        }
        // 排序白名单映射：排序字段必须白名单映射，防止 SQL 注入
        String sortKey = sort == null ? "default" : sort;
        switch (sortKey) {
            case "sales" -> qw.orderByDesc(Product::getSales).orderByDesc(Product::getId);
            case "price_asc" -> qw.orderByAsc(Product::getPrice).orderByDesc(Product::getId);
            case "price_desc" -> qw.orderByDesc(Product::getPrice).orderByDesc(Product::getId);
            default -> qw.orderByDesc(Product::getId);
        }

        Page<Product> page = productMapper.selectPage(
                new Page<>(pageNum, Math.min(Math.max(pageSize, 1), 50)), qw);
        PageVO<ProductVO> pageVO = new PageVO<>();
        pageVO.setTotal(page.getTotal());
        pageVO.setPageNum(page.getCurrent());
        pageVO.setPageSize(page.getSize());
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        // 公开接口脱敏：库存属商家内部经营数据，不向用户端暴露
        pageVO.setRecords(page.getRecords().stream()
                .map(p -> {
                    ProductVO vo = toVO(p, categoryIdNameMap);
                    vo.setStock(null);
                    return vo;
                })
                .toList());
        return pageVO;
    }

    @Override
    @Cacheable(cacheNames = "productDetail", key = "#id")
    public ProductVO getPublicDetail(Long id) {
        Product product = productMapper.selectById(id);
        // 不存在或已下架都视为不存在
        if (product == null || product.getStatus() == 0) {
            throw new BizException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        // 公开接口脱敏：不返回库存
        ProductVO vo = toVO(product, loadCategoryNames(List.of(product)));
        vo.setStock(null);
        return vo;
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
     */
    @Override
    public List<ProductVO> getHotProducts(int limit) {
        // a. 先查缓存
        String cached = stringRedisTemplate.opsForValue().get(HOT_PRODUCTS_KEY);
        if (cached != null) {
            if (cached.isEmpty()) {
                // 命中空值标记：说明此前查库无结果（防穿透），直接返回空列表
                return List.of();
            }
            return fromJson(cached);
        }

        // b. 未命中：抢互斥锁，抢到者负责重建缓存（防击穿）
        String token = UUID.randomUUID().toString();
        if (redisLockUtil.tryLock(HOT_PRODUCTS_LOCK_KEY, 3, token)) {
            try {
                // double-check：抢锁期间可能已有其他线程把缓存回填好了，再查一次避免重复回源
                String cached2 = stringRedisTemplate.opsForValue().get(HOT_PRODUCTS_KEY);
                if (cached2 != null) {
                    return cached2.isEmpty() ? List.of() : fromJson(cached2);
                }
                List<ProductVO> hot = queryHotFromDb(limit);
                if (hot.isEmpty()) {
                    // 结果为空：写空值标记（短 TTL 30s），防止穿透
                    stringRedisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, EMPTY_MARKER, EMPTY_TTL);
                } else {
                    // 非空：写 JSON 缓存，TTL = 5 分钟 + 随机 0~60 秒（防雪崩）
                    stringRedisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, toJson(hot), randomTtl());
                }
                return hot;
            } finally {
                // 无论成败都释放锁（Lua 原子释放，只删自己的锁）
                redisLockUtil.unlock(HOT_PRODUCTS_LOCK_KEY, token);
            }
        }

        // c. 抢锁失败：说明他人正在重建，短暂休眠后重试几次
        for (int i = 0; i < 3; i++) {
            sleep(100);
            String c = stringRedisTemplate.opsForValue().get(HOT_PRODUCTS_KEY);
            if (c != null) {
                return c.isEmpty() ? List.of() : fromJson(c);
            }
        }
        // 重试仍无缓存：降级直接查库返回（宁可多查库，也不阻塞请求）
        return queryHotFromDb(limit);
    }

    /**
     * 失效指定商品详情缓存（供 OrderServiceImpl 下单扣库存等旁路写操作调用）。
     *
     * <p>下单扣库存走 {@link ProductMapper#deductStock}，绕过了 ProductService，
     * 因此这里提供一个带 @CacheEvict 的入口，由下单成功方主动调用，保证写后缓存一致。</p>
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
     */
    @Override
    public void evictHotProducts() {
        evictHotProductsCache();
    }

    /**
     * 手动删除热门商品缓存 key（手写缓存的失效方式）。
     */
    private void evictHotProductsCache() {
        stringRedisTemplate.delete(HOT_PRODUCTS_KEY);
    }

    /**
     * 从库里取销量 top N 的上架商品并脱敏。
     */
    private List<ProductVO> queryHotFromDb(int limit) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product> qw =
                Wrappers.lambdaQuery();
        qw.eq(Product::getStatus, 1);
        qw.orderByDesc(Product::getSales).orderByDesc(Product::getId);
        // 用物理分页取前 limit 条（searchCount=false 省掉 count 查询）
        Page<Product> page = productMapper.selectPage(new Page<>(1, Math.max(limit, 1), false), qw);
        Map<Long, String> categoryIdNameMap = loadCategoryNames(page.getRecords());
        // 公开接口脱敏：不返回库存
        return page.getRecords().stream()
                .map(p -> {
                    ProductVO vo = toVO(p, categoryIdNameMap);
                    vo.setStock(null);
                    return vo;
                })
                .toList();
    }

    /**
     * 热门商品缓存 TTL：5 分钟 + 随机 0~60 秒偏移，把过期时间打散，防缓存雪崩。
     */
    private Duration randomTtl() {
        int offsetSeconds = ThreadLocalRandom.current().nextInt(0, 61);
        return HOT_PRODUCTS_TTL.plusSeconds(offsetSeconds);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * List&lt;ProductVO&gt; 序列化为 JSON 字符串（存入 Redis）。
     */
    private String toJson(List<ProductVO> list) {
        try {
            return jsonMapper.writeValueAsString(list);
        } catch (JacksonException e) {
            throw new RuntimeException("热门商品缓存序列化失败", e);
        }
    }

    /**
     * JSON 字符串反序列化为 List&lt;ProductVO&gt;。
     */
    private List<ProductVO> fromJson(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<ProductVO>>() {
            });
        } catch (JacksonException e) {
            throw new RuntimeException("热门商品缓存反序列化失败", e);
        }
    }

    /**
     * 查询商品所属分类的名称映射，避免逐条查库（N+1）。
     */
    private Map<Long, String> loadCategoryNames(List<Product> products) {
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .distinct()
                .toList();
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectByIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getCategoryName));
    }

    /**
     * 查询商品并做归属校验：不存在抛 PRODUCT_NOT_FOUND，非自己店铺的商品抛 403。
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
     * 商品 → 视图对象（无分类名映射，用于单条场景）。
     */
    private ProductVO toVO(Product product) {
        return toVO(product, Map.of());
    }

    /**
     * 商品 → 视图对象（附带分类名映射）。
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
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }

}