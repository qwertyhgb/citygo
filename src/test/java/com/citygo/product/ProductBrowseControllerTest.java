package com.citygo.product;

import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户端商品浏览接口测试。
 *
 * <p>测试数据直接用 Mapper 造（店铺+商品），确保过滤/排序断言可控。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductBrowseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 造一个正常店铺，返回店铺 id */
    private Long insertShop(String name) {
        Shop shop = new Shop();
        shop.setMerchantId(1L);
        shop.setShopName(name);
        shop.setCity("北京");
        shop.setAddress("测试地址");
        shop.setScore(BigDecimal.ZERO);
        shop.setMonthlySales(0);
        shop.setOpenStatus(1);
        shop.setStatus(1);
        shopMapper.insert(shop);
        return shop.getId();
    }

    /** 造一个商品（默认上架），返回商品 id */
    private Long insertProduct(Long shopId, Long categoryId, String name,
                               BigDecimal price, Integer sales, Integer stock, Integer status) {
        Product product = new Product();
        product.setShopId(shopId);
        product.setCategoryId(categoryId);
        product.setProductName(name);
        product.setPrice(price);
        product.setStock(stock);
        product.setSales(sales);
        product.setStatus(status);
        productMapper.insert(product);
        return product.getId();
    }

    /**
     * 场景：默认排序（id desc，限定测试店铺隔离库中其他数据）。
     */
    @Test
    void list_default_sort() throws Exception {
        Long shopId = insertShop("排序店铺");
        Long p1 = insertProduct(shopId, 1L, "商品A", new BigDecimal("10.00"), 5, 10, 1);
        Long p2 = insertProduct(shopId, 1L, "商品B", new BigDecimal("20.00"), 5, 10, 1);
        String body = mockMvc.perform(get("/api/products").param("shopId", String.valueOf(shopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode records = objectMapper.readTree(body).path("data").path("records");
        org.junit.jupiter.api.Assertions.assertEquals(2, records.size());
        org.junit.jupiter.api.Assertions.assertEquals(p2, records.get(0).path("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(p1, records.get(1).path("id").asLong());
    }

    /**
     * 场景：分类筛选。
     */
    @Test
    void list_filter_category() throws Exception {
        Long shopId = insertShop("分类店铺");
        insertProduct(shopId, 1L, "火锅类商品", new BigDecimal("10.00"), 5, 10, 1);
        insertProduct(shopId, 2L, "烧烤类商品", new BigDecimal("10.00"), 5, 10, 1);
        mockMvc.perform(get("/api/products")
                        .param("shopId", String.valueOf(shopId)).param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].categoryName").value("火锅"));
    }

    /**
     * 场景：商品名模糊筛选。
     */
    @Test
    void list_filter_keyword() throws Exception {
        Long shopId = insertShop("关键词店铺");
        insertProduct(shopId, 1L, "麻辣毛肚", new BigDecimal("10.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "香辣鸡腿", new BigDecimal("10.00"), 5, 10, 1);
        mockMvc.perform(get("/api/products")
                        .param("shopId", String.valueOf(shopId)).param("keyword", "毛肚"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].productName").value("麻辣毛肚"));
    }

    /**
     * 场景：价格区间筛选。
     */
    @Test
    void list_filter_price_range() throws Exception {
        Long shopId = insertShop("价格店铺");
        insertProduct(shopId, 1L, "便宜菜", new BigDecimal("5.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "中等菜", new BigDecimal("25.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "昂贵菜", new BigDecimal("95.00"), 5, 10, 1);
        mockMvc.perform(get("/api/products")
                        .param("shopId", String.valueOf(shopId))
                        .param("minPrice", "10").param("maxPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].productName").value("中等菜"));
    }

    /**
     * 场景：按销量倒序（限定测试店铺）。
     */
    @Test
    void list_sort_by_sales() throws Exception {
        Long shopId = insertShop("销量店铺");
        insertProduct(shopId, 1L, "低销量", new BigDecimal("10.00"), 10, 10, 1);
        insertProduct(shopId, 1L, "高销量", new BigDecimal("10.00"), 999, 10, 1);
        mockMvc.perform(get("/api/products")
                        .param("sort", "sales").param("shopId", String.valueOf(shopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].productName").value("高销量"));
    }

    /**
     * 场景：价格升序/降序。
     */
    @Test
    void list_sort_by_price_asc_desc() throws Exception {
        Long shopId = insertShop("价格排序店铺");
        insertProduct(shopId, 1L, "P10", new BigDecimal("10.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "P50", new BigDecimal("50.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "P30", new BigDecimal("30.00"), 5, 10, 1);
        // 升序：第一页第一条应为最便宜（排除此前测试残留，仅断言本店数据顺序）
        mockMvc.perform(get("/api/products").param("sort", "price_asc").param("shopId", String.valueOf(shopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].productName").value("P10"));
        mockMvc.perform(get("/api/products").param("sort", "price_desc").param("shopId", String.valueOf(shopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].productName").value("P50"));
    }

    /**
     * 场景：下架商品不出现在列表。
     */
    @Test
    void list_only_onsale() throws Exception {
        Long shopId = insertShop("上下架店铺");
        insertProduct(shopId, 1L, "在售商品", new BigDecimal("10.00"), 5, 10, 1);
        insertProduct(shopId, 1L, "下架商品", new BigDecimal("10.00"), 5, 10, 0);
        mockMvc.perform(get("/api/products").param("shopId", String.valueOf(shopId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].productName").value("在售商品"));
    }

    /**
     * 场景：详情成功且 stock 为 null（公开接口脱敏）。
     */
    @Test
    void detail_success() throws Exception {
        Long shopId = insertShop("详情店铺");
        Long id = insertProduct(shopId, 1L, "详情商品", new BigDecimal("10.00"), 5, 88, 1);
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.productName").value("详情商品"))
                .andExpect(jsonPath("$.data.stock").value(org.hamcrest.Matchers.nullValue()));
    }

    /**
     * 场景：下架商品详情 404。
     */
    @Test
    void detail_offsale_not_found() throws Exception {
        Long shopId = insertShop("下架详情店铺");
        Long id = insertProduct(shopId, 1L, "已下架", new BigDecimal("10.00"), 5, 10, 0);
        mockMvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：不存在的商品详情 404。
     */
    @Test
    void detail_not_found() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 99999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：未登录访问 /api/products/my 返回 HTTP 401（验证白名单通配没有误伤"我的"接口）。
     */
    @Test
    void unauthorized_my_still_401() throws Exception {
        mockMvc.perform(get("/api/products/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

}