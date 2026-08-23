package com.citygo.shop;

import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
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
 * 用户端店铺浏览接口测试。
 *
 * <p>测试数据直接用 Mapper 造（店铺），确保过滤/排序断言可控。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShopBrowseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 造一个店铺（默认 status=1），返回店铺 id */
    private Long insertShop(String name, String city, BigDecimal score, Integer monthlySales, Integer status) {
        Shop shop = new Shop();
        shop.setMerchantId(1L);
        shop.setShopName(name);
        shop.setCity(city);
        shop.setAddress("测试地址");
        shop.setScore(score);
        shop.setMonthlySales(monthlySales);
        shop.setOpenStatus(1);
        shop.setStatus(status);
        shopMapper.insert(shop);
        return shop.getId();
    }

    /**
     * 场景：默认排序（id desc，新店优先）。
     */
    @Test
    void list_default_sort() throws Exception {
        Long s1 = insertShop("店铺A", "北京", new BigDecimal("4.0"), 100, 1);
        Long s2 = insertShop("店铺B", "北京", new BigDecimal("4.5"), 200, 1);
        String body = mockMvc.perform(get("/api/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode records = objectMapper.readTree(body).path("data").path("records");
        org.junit.jupiter.api.Assertions.assertTrue(records.size() >= 2);
        // 后插入的 id 更大，应排在前面
        org.junit.jupiter.api.Assertions.assertEquals(s2, records.get(0).path("id").asLong());
        org.junit.jupiter.api.Assertions.assertEquals(s1, records.get(1).path("id").asLong());
    }

    /**
     * 场景：按城市精确筛选（用唯一城市名隔离库中其他数据）。
     */
    @Test
    void list_filter_by_city() throws Exception {
        String uniqueCity = "测试市" + System.currentTimeMillis() % 1_000_000;
        insertShop("北京店", uniqueCity, new BigDecimal("4.0"), 10, 1);
        insertShop("上海店", uniqueCity, new BigDecimal("4.0"), 10, 1);
        // 同一唯一城市下两条，再按关键词精确取一条
        mockMvc.perform(get("/api/shops").param("city", uniqueCity).param("keyword", "上海店"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].shopName").value("上海店"));
    }

    /**
     * 场景：按店铺名模糊筛选（用唯一前缀隔离；like 匹配连续子串）。
     */
    @Test
    void list_filter_by_keyword() throws Exception {
        String unique = "kw" + System.currentTimeMillis() % 1_000_000;
        insertShop(unique + "海底捞火锅", "北京", new BigDecimal("4.0"), 10, 1);
        insertShop(unique + "肯德基快餐", "北京", new BigDecimal("4.0"), 10, 1);
        mockMvc.perform(get("/api/shops").param("keyword", unique + "海底捞"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].shopName").value(unique + "海底捞火锅"));
    }

    /**
     * 场景：按月销量倒序（用唯一城市隔离，避免库中其他高销量店铺干扰）。
     */
    @Test
    void list_sort_by_sales() throws Exception {
        String uniqueCity = "销量市" + System.currentTimeMillis() % 1_000_000;
        insertShop("低销量", uniqueCity, new BigDecimal("4.0"), 10, 1);
        insertShop("高销量", uniqueCity, new BigDecimal("4.0"), 999, 1);
        mockMvc.perform(get("/api/shops").param("sort", "sales").param("city", uniqueCity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].shopName").value("高销量"));
    }

    /**
     * 场景：店铺详情成功。
     */
    @Test
    void detail_success() throws Exception {
        Long id = insertShop("详情店", "北京", new BigDecimal("4.8"), 66, 1);
        mockMvc.perform(get("/api/shops/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.shopName").value("详情店"));
    }

    /**
     * 场景：店铺详情不存在。断言 code=404。
     */
    @Test
    void detail_not_found() throws Exception {
        mockMvc.perform(get("/api/shops/{id}", 99999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：店铺内商品分页（走公开浏览接口，含库存脱敏断言）。
     */
    @Test
    void shop_products_page() throws Exception {
        Long shopId = insertShop("商品店铺", "北京", new BigDecimal("4.0"), 10, 1);
        mockMvc.perform(get("/api/shops/{id}/products", shopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：禁用店铺不出现在列表、详情 404。
     */
    @Test
    void disabled_shop_hidden() throws Exception {
        Long id = insertShop("禁用店", "北京", new BigDecimal("4.0"), 10, 0);
        // 列表不出现
        mockMvc.perform(get("/api/shops").param("city", "北京").param("keyword", "禁用店"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 详情 404
        mockMvc.perform(get("/api/shops/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

}