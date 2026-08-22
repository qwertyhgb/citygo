package com.citygo.product;

import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品管理接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 测试用固定分类（V3 迁移的"火锅"，id=1） */
    private static final long CATEGORY_ID = 1L;

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    /**
     * 注册商家并登录，返回 token。
     */
    private String registerMerchantAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/merchants/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\","
                                + "\"merchantName\":\"餐厅" + uniqueSuffix() + "\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    /**
     * 用 token 创建店铺，返回店铺 id。
     */
    private Long createShop(String token) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"商品店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 用 token 在指定店铺创建商品，返回商品 id。
     */
    private Long createProduct(String token, Long shopId, String name, String extra) throws Exception {
        String body = "{\"shopId\":" + shopId + ",\"categoryId\":" + CATEGORY_ID
                + ",\"productName\":\"" + name + "\",\"price\":19.90,\"stock\":10" + extra + "}";
        String resp = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 场景：成功创建商品。断言 stock=10、sales=0。
     */
    @Test
    void create_product_success() throws Exception {
        String token = registerMerchantAndLogin("pc" + uniqueSuffix());
        Long shopId = createShop(token);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":" + CATEGORY_ID
                                + ",\"productName\":\"毛肚\",\"price\":38.00,\"stock\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stock").value(10))
                .andExpect(jsonPath("$.data.sales").value(0));
    }

    /**
     * 场景：负库存创建商品。断言 code=400。
     */
    @Test
    void create_product_illegal_stock() throws Exception {
        String token = registerMerchantAndLogin("pr" + uniqueSuffix());
        Long shopId = createShop(token);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":" + CATEGORY_ID
                                + ",\"productName\":\"毛肚\",\"price\":38.00,\"stock\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 场景：分类不存在。断言 code=404。
     */
    @Test
    void create_product_category_not_found() throws Exception {
        String token = registerMerchantAndLogin("pn" + uniqueSuffix());
        Long shopId = createShop(token);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":99999,"
                                + "\"productName\":\"毛肚\",\"price\":38.00,\"stock\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：用其它商家的 token 在他人店铺创建商品。断言 code=403（越权）。
     */
    @Test
    void create_product_other_shop_forbidden() throws Exception {
        String tokenA = registerMerchantAndLogin("pa" + uniqueSuffix());
        String tokenB = registerMerchantAndLogin("pb" + uniqueSuffix());
        Long shopA = createShop(tokenA);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopA + ",\"categoryId\":" + CATEGORY_ID
                                + ",\"productName\":\"毛肚\",\"price\":38.00,\"stock\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：修改商品价格后查库确认。
     */
    @Test
    void update_product_price() throws Exception {
        String token = registerMerchantAndLogin("pu" + uniqueSuffix());
        Long shopId = createShop(token);
        Long productId = createProduct(token, shopId, "毛肚", "");
        mockMvc.perform(put("/api/products/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"毛肚\",\"price\":55.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        Product product = productMapper.selectById(productId);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                new java.math.BigDecimal("55.50").compareTo(product.getPrice()));
    }

    /**
     * 场景：切换商品上下架状态后查库确认。
     */
    @Test
    void toggle_product_status() throws Exception {
        String token = registerMerchantAndLogin("pts" + uniqueSuffix());
        Long shopId = createShop(token);
        Long productId = createProduct(token, shopId, "毛肚", "");
        mockMvc.perform(patch("/api/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(0, productMapper.selectById(productId).getStatus());
    }

    /**
     * 场景：修改商品库存后查库确认。
     */
    @Test
    void update_product_stock() throws Exception {
        String token = registerMerchantAndLogin("psk" + uniqueSuffix());
        Long shopId = createShop(token);
        Long productId = createProduct(token, shopId, "毛肚", "");
        mockMvc.perform(patch("/api/products/" + productId + "/stock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":99}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(99, productMapper.selectById(productId).getStock());
    }

    /**
     * 场景：我的商品分页。造 3 条，pageSize=2，断言 total=3、records=2、按 id 倒序。
     */
    @Test
    void my_products_page() throws Exception {
        String token = registerMerchantAndLogin("pp" + uniqueSuffix());
        Long shopId = createShop(token);
        createProduct(token, shopId, "商品A", "");
        createProduct(token, shopId, "商品B", "");
        createProduct(token, shopId, "商品C", "");
        String body = mockMvc.perform(get("/api/products/my")
                        .header("Authorization", "Bearer " + token)
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(3, data.path("total").asLong());
        JsonNode records = data.path("records");
        org.junit.jupiter.api.Assertions.assertEquals(2, records.size());
        // 按 id 倒序
        org.junit.jupiter.api.Assertions.assertTrue(
                records.get(0).path("id").asLong() > records.get(1).path("id").asLong(),
                "记录应按 id 倒序");
    }

}