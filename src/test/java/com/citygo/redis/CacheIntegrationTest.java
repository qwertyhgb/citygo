package com.citygo.redis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缓存集成测试（店铺/商品详情 Spring Cache + 热门商品手写三防 + 限流）。
 *
 * <p>注意：Redis 非事务，缓存写入不会随测试回滚，故用 @BeforeEach 清理关键 key，
 * 避免测试之间相互干扰。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CacheIntegrationTest {

    private static final String HOT_KEY = "citygo:cache:hotProducts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate redis;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanRedis() {
        // 清理热门商品缓存与限流计数，避免测试间残留相互干扰
        redis.delete(HOT_KEY);
        Set<String> rateKeys = redis.keys("citygo:rate:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redis.delete(rateKeys);
        }
    }

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    /** 注册商家，返回 token */
    private String registerMerchant(String username) throws Exception {
        mockMvc.perform(post("/api/merchants/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\","
                                + "\"merchantName\":\"餐厅" + uniqueSuffix() + "\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    /** 商家 token 建店铺，返回 shopId */
    private long createShop(String merchantToken) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"缓存店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 商家建商品，返回 productId */
    private long createProduct(String merchantToken, long shopId, String name, int stock) throws Exception {
        String resp = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":1,"
                                + "\"productName\":\"" + name + "\",\"price\":20.00,\"stock\":" + stock + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 造一个正常店铺（直接 Mapper，供热门商品测试，无需商家归属） */
    private long insertShop(String name) {
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

    /** 造一个上架商品（直接 Mapper，可指定销量） */
    private long insertProduct(long shopId, String name, int sales) {
        Product product = new Product();
        product.setShopId(shopId);
        product.setCategoryId(1L);
        product.setProductName(name);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(100);
        product.setSales(sales);
        product.setStatus(1);
        productMapper.insert(product);
        return product.getId();
    }

    /**
     * 场景：店铺详情第一次调用写缓存，Redis 存在 shopDetail::{id}，第二次调用结果一致。
     */
    @Test
    void shop_detail_cached() throws Exception {
        String merchant = registerMerchant("scm" + uniqueSuffix());
        long shopId = createShop(merchant);
        mockMvc.perform(get("/api/shops/{id}", shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 缓存 key 已存在
        assertTrue(Boolean.TRUE.equals(redis.hasKey("shopDetail::" + shopId)), "店铺详情缓存 key 应存在");
        // 第二次调用仍成功（命中缓存）
        mockMvc.perform(get("/api/shops/{id}", shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：商家修改店铺后，shopDetail::{id} 缓存失效（联动）。
     */
    @Test
    void shop_update_evicts_cache() throws Exception {
        String merchant = registerMerchant("sum" + uniqueSuffix());
        long shopId = createShop(merchant);
        mockMvc.perform(get("/api/shops/{id}", shopId)).andExpect(status().isOk());
        assertTrue(Boolean.TRUE.equals(redis.hasKey("shopDetail::" + shopId)));
        // 更新店铺（归属校验通过）
        mockMvc.perform(put("/api/shops/{id}", shopId)
                        .header("Authorization", "Bearer " + merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"改过名的店\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("shopDetail::" + shopId)), "更新店铺后应失效详情缓存");
    }

    /**
     * 场景：商品详情缓存 + 更新商品后失效。
     */
    @Test
    void product_detail_cached_and_evict_on_update() throws Exception {
        String merchant = registerMerchant("pm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "缓存商品", 50);
        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("productDetail::" + productId)));
        // 更新商品 → 缓存失效
        mockMvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"改名商品\",\"price\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("productDetail::" + productId)), "更新商品后应失效详情缓存");
    }

    /**
     * 场景：热门商品按销量倒序，且 stock 已脱敏。
     */
    @Test
    void hot_products_returns_top_sales() throws Exception {
        long shopId = insertShop("热门店铺");
        String topName = "最热商品" + uniqueSuffix();
        insertProduct(shopId, "普通商品" + uniqueSuffix(), 0);
        insertProduct(shopId, topName, Integer.MAX_VALUE);
        String body = mockMvc.perform(get("/api/products/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        assertTrue(data.size() >= 1);
        assertEquals(topName, data.get(0).path("productName").asText());
        assertTrue(data.get(0).path("stock").isNull(), "热门商品接口应脱敏 stock");
    }

    /**
     * 场景：热门商品二次调用命中 Redis 缓存（key 存在且值为 JSON 数组）。
     */
    @Test
    void hot_products_second_call_from_cache() throws Exception {
        long shopId = insertShop("二次缓存店铺");
        insertProduct(shopId, "缓存热门" + uniqueSuffix(), 9999);
        // 第一次：回源并写缓存
        mockMvc.perform(get("/api/products/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // Redis key 存在且值是 JSON 数组
        String cached = redis.opsForValue().get(HOT_KEY);
        assertNotNull(cached, "热门商品缓存应已写入");
        assertTrue(cached.startsWith("["), "缓存值应为 JSON 数组");
        // 第二次：命中缓存返回成功
        mockMvc.perform(get("/api/products/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：无热门商品时，缓存空值标记（防穿透）。
     */
    @Test
    void hot_products_caches_empty() throws Exception {
        // 下架所有商品，模拟"无热门商品"
        productMapper.update(null, Wrappers.<Product>lambdaUpdate().set(Product::getStatus, 0));
        mockMvc.perform(get("/api/products/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 空值标记已写入（空字符串）
        assertEquals("", redis.opsForValue().get(HOT_KEY), "无数据时应缓存空值标记");
    }

    /**
     * 场景：热门接口限流，1 秒内超过 5 次 → 第 6 次返回 429。
     */
    @Test
    void hot_products_rate_limited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/products/hot"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
        mockMvc.perform(get("/api/products/hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429));
    }

}