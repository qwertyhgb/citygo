package com.citygo.search;

import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.citygo.product.vo.ProductVO;
import com.citygo.search.doc.ProductDoc;
import com.citygo.search.doc.ShopDoc;
import com.citygo.search.service.ProductSearchService;
import com.citygo.search.service.ShopSearchService;
import com.citygo.shop.vo.ShopBrowseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Elasticsearch 搜索集成测试（连真实 ES 容器）。
 *
 * <p>ES 写入有约 1 秒刷新延迟，每次写入后调用
 * {@code elasticsearchOperations.indexOps(...).refresh()} 强制刷新（ES 近实时特性）。</p>
 *
 * <p>注意：本类仍标注 {@code @Transactional}（DB 回滚），ES 文档不随事务回滚。
 * 为保证用例隔离，@BeforeEach 会<b>清空并重建 citygo_shop / citygo_product 索引</b>
 * （只碰 citygo_ 前缀，绝不动 knowflow 索引）；同步通过 searchService 直接写入，
 * 不依赖业务 afterCommit（afterCommit 在回滚测试里不会触发）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ShopSearchService shopSearchService;

    @Autowired
    private ProductSearchService productSearchService;

    @Autowired
    private ElasticsearchOperations operations;

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    @BeforeEach
    void resetIndexes() {
        resetIndex(ShopDoc.class);
        resetIndex(ProductDoc.class);
    }

    /** 清空并重建一个 citygo 索引（含 IK/geo 映射） */
    private void resetIndex(Class<?> clazz) {
        IndexOperations idx = operations.indexOps(clazz);
        if (Boolean.TRUE.equals(idx.exists())) {
            idx.delete();
        }
        idx.create();
        idx.putMapping();
    }

    private void refresh() {
        operations.indexOps(ShopDoc.class).refresh();
        operations.indexOps(ProductDoc.class).refresh();
    }

    private Shop insertShop(String name, String city, String lat, String lng,
                            String score, int monthlySales) {
        Shop shop = new Shop();
        shop.setMerchantId(1L);
        shop.setShopName(name);
        shop.setCity(city);
        shop.setDistrict("城区");
        shop.setAddress("测试地址");
        if (lat != null) {
            shop.setLatitude(new BigDecimal(lat));
            shop.setLongitude(new BigDecimal(lng));
        }
        shop.setScore(score == null ? BigDecimal.ZERO : new BigDecimal(score));
        shop.setMonthlySales(monthlySales);
        shop.setOpenStatus(1);
        shop.setStatus(1);
        shopMapper.insert(shop);
        // 直接同步 ES（测试不走业务 afterCommit）
        shopSearchService.syncShop(shopMapper.selectById(shop.getId()));
        return shop;
    }

    private Product insertProduct(long shopId, String name, String description, int price,
                                  int sales, int stock, int status) {
        Product p = new Product();
        p.setShopId(shopId);
        p.setCategoryId(1L);
        p.setProductName(name);
        p.setDescription(description);
        p.setPrice(new BigDecimal(price));
        p.setSales(sales);
        p.setStock(stock);
        p.setStatus(status);
        productMapper.insert(p);
        productSearchService.syncProduct(productMapper.selectById(p.getId()));
        return p;
    }

    /**
     * 场景：启动后 citygo_shop / citygo_product 索引存在。
     */
    @Test
    void index_initialized() {
        assertTrue(Boolean.TRUE.equals(operations.indexOps(ShopDoc.class).exists()), "citygo_shop 索引应存在");
        assertTrue(Boolean.TRUE.equals(operations.indexOps(ProductDoc.class).exists()), "citygo_product 索引应存在");
    }

    /**
     * 场景：中文关键词全文检索（IK 分词）命中；无关词不命中。
     */
    @Test
    void shop_search_keyword_chinese() {
        insertShop("老王火锅店" + uniqueSuffix(), "上海", "31.2201", "121.4801", "4.5", 100);
        refresh();
        PageVO<ShopBrowseVO> hit = shopSearchService.searchShops("火锅", null, null, null, null, "default", 1, 10);
        assertTrue(hit.getTotal() > 0, "搜'火锅'应命中");
        assertTrue(hit.getRecords().stream().anyMatch(v -> v.getShopName().contains("火锅")));
        PageVO<ShopBrowseVO> miss = shopSearchService.searchShops("烧烤", null, null, null, null, "default", 1, 10);
        assertEquals(0, miss.getTotal(), "搜'烧烤'不应命中火锅店");
    }

    /**
     * 场景：城市过滤。
     */
    @Test
    void shop_search_city_filter() {
        String c1 = "city" + uniqueSuffix();
        insertShop("城市店A" + uniqueSuffix(), c1, "31.22", "121.48", "4.0", 10);
        insertShop("城市店B" + uniqueSuffix(), "浦西" + uniqueSuffix(), "31.22", "121.48", "4.0", 10);
        refresh();
        PageVO<ShopBrowseVO> page = shopSearchService.searchShops(null, c1, null, null, null, "default", 1, 10);
        assertEquals(1, page.getTotal(), "只应命中城市=" + c1 + " 的店铺");
    }

    /**
     * 场景：评分降序。
     */
    @Test
    void shop_search_sort_score() {
        insertShop("高评分店" + uniqueSuffix(), "北京", "31.22", "121.48", "5.0", 10);
        insertShop("低评分店" + uniqueSuffix(), "北京", "31.22", "121.48", "1.0", 10);
        refresh();
        PageVO<ShopBrowseVO> page = shopSearchService.searchShops(null, "北京", null, null, null, "score", 1, 10);
        assertEquals("高评分店", page.getRecords().get(0).getShopName().substring(0, 4));
    }

    /**
     * 场景：按距离升序（近的在前）。
     */
    @Test
    void shop_search_geo_distance() {
        // 原点 (31.22, 121.48)
        insertShop("近店" + uniqueSuffix(), "上海", "31.2201", "121.4801", "4.0", 10);
        insertShop("远店" + uniqueSuffix(), "上海", "31.60", "121.90", "4.0", 10);
        refresh();
        PageVO<ShopBrowseVO> page = shopSearchService.searchShops(
                null, null, 31.22, 121.48, null, "distance", 1, 10);
        assertTrue(page.getRecords().get(0).getShopName().startsWith("近店"), "距离近的应排在最前");
    }

    /**
     * 场景：distanceKm 过滤掉远处店铺。
     */
    @Test
    void shop_search_geo_filter() {
        insertShop("圈内店" + uniqueSuffix(), "上海", "31.2201", "121.4801", "4.0", 10);
        insertShop("圈外店" + uniqueSuffix(), "上海", "31.60", "121.90", "4.0", 10);
        refresh();
        PageVO<ShopBrowseVO> page = shopSearchService.searchShops(
                null, null, 31.22, 121.48, 1.0, "default", 1, 10);
        assertEquals(1, page.getTotal(), "距离 1km 内只应有圈内店");
        assertTrue(page.getRecords().get(0).getShopName().startsWith("圈内店"));
    }

    /**
     * 场景：商品名或描述 multiMatch 命中。
     */
    @Test
    void product_search_keyword_multi_field() {
        long shopId = insertShop("商品店" + uniqueSuffix(), "北京", null, null, "4.0", 0).getId();
        insertProduct(shopId, "毛肚" + uniqueSuffix(), "正宗川味火锅底料", 30, 10, 50, 1);
        refresh();
        // 描述命中
        PageVO<ProductVO> byDesc = productSearchService.searchProducts("川味", null, null, null, "default", 1, 10);
        assertTrue(byDesc.getTotal() > 0, "描述含'川味'应命中");
        // 名称命中
        PageVO<ProductVO> byName = productSearchService.searchProducts("毛肚", null, null, null, "default", 1, 10);
        assertTrue(byName.getTotal() > 0, "名称含'毛肚'应命中");
    }

    /**
     * 场景：价格区间过滤。
     */
    @Test
    void product_search_price_range() {
        long shopId = insertShop("价格店" + uniqueSuffix(), "北京", null, null, "4.0", 0).getId();
        insertProduct(shopId, "便宜菜", "菜", 20, 10, 50, 1);
        insertProduct(shopId, "昂贵菜", "菜", 80, 10, 50, 1);
        refresh();
        PageVO<ProductVO> page = productSearchService.searchProducts(
                null, null, new BigDecimal("10"), new BigDecimal("50"), "default", 1, 10);
        assertEquals(1, page.getTotal(), "10~50 区间只应命中便宜菜");
        assertEquals("便宜菜", page.getRecords().get(0).getProductName());
    }

    /**
     * 场景：价格升降序。
     */
    @Test
    void product_search_sort_price() {
        long shopId = insertShop("排序店" + uniqueSuffix(), "北京", null, null, "4.0", 0).getId();
        insertProduct(shopId, "P10" + uniqueSuffix(), "", 10, 1, 50, 1);
        insertProduct(shopId, "P50" + uniqueSuffix(), "", 50, 1, 50, 1);
        refresh();
        PageVO<ProductVO> asc = productSearchService.searchProducts(null, null, null, null, "price_asc", 1, 10);
        assertTrue(asc.getRecords().get(0).getProductName().startsWith("P10"), "升序最便宜在前");
        PageVO<ProductVO> desc = productSearchService.searchProducts(null, null, null, null, "price_desc", 1, 10);
        assertTrue(desc.getRecords().get(0).getProductName().startsWith("P50"), "降序最贵在前");
    }

    /**
     * 场景：下架商品搜索不到（status=0 被过滤）。
     */
    @Test
    void product_offshelf_not_in_search() {
        long shopId = insertShop("下架店" + uniqueSuffix(), "北京", null, null, "4.0", 0).getId();
        insertProduct(shopId, "在售商品", "", 20, 10, 50, 1);
        insertProduct(shopId, "下架商品", "", 20, 10, 50, 0);
        refresh();
        PageVO<ProductVO> page = productSearchService.searchProducts(null, null, null, null, "default", 1, 10);
        assertEquals(1, page.getTotal(), "下架商品不应出现在搜索");
        assertEquals("在售商品", page.getRecords().get(0).getProductName());
    }

    /**
     * 场景：公开搜索 stock 脱敏（null）且未登录可访问公开接口。
     */
    @Test
    void product_search_result_stock_null_and_public() throws Exception {
        long shopId = insertShop("脱敏店" + uniqueSuffix(), "北京", null, null, "4.0", 0).getId();
        insertProduct(shopId, "脱敏商品", "", 20, 10, 50, 1);
        refresh();
        PageVO<ProductVO> page = productSearchService.searchProducts(null, null, null, null, "default", 1, 10);
        assertEquals(null, page.getRecords().get(0).getStock(), "公开搜索 stock 应脱敏为 null");
        // 未登录可访问公开搜索接口
        mockMvc.perform(get("/api/search/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

}