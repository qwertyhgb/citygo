package com.citygo.cart;

import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车接口测试。
 *
 * <p>购物车数据存 Redis（带 TTL），测试产生的 key 会残留但自动过期，可接受。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    /** 造一个上架商品，返回 productId */
    private Long insertOnSaleProduct(String name) {
        Product product = new Product();
        product.setShopId(1L);
        product.setCategoryId(1L);
        product.setProductName(name);
        product.setPrice(new BigDecimal("20.00"));
        product.setStock(100);
        product.setSales(0);
        product.setStatus(1);
        productMapper.insert(product);
        return product.getId();
    }

    /**
     * 场景：加入购物车成功，返回购物车种数 1。
     */
    @Test
    void add_item() throws Exception {
        String token = registerAndLogin("ca" + uniqueSuffix());
        Long pid = insertOnSaleProduct("火锅");
        mockMvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(1));
    }

    /**
     * 场景：重复加购数量累加（购车种数不变，数量变 2+3=5）。
     */
    @Test
    void add_accumulate() throws Exception {
        String token = registerAndLogin("cb" + uniqueSuffix());
        Long pid = insertOnSaleProduct("烧烤");
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(5));
    }

    /**
     * 场景：更新数量为 0 会删除该条目。
     */
    @Test
    void update_quantity_zero_removes() throws Exception {
        String token = registerAndLogin("cc" + uniqueSuffix());
        Long pid = insertOnSaleProduct("奶茶");
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/cart/items/" + pid).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /**
     * 场景：查看购物车含商品与小计计算（price 20 * qty 2 = subtotal 40）。
     */
    @Test
    void view_cart_with_items() throws Exception {
        String token = registerAndLogin("cd" + uniqueSuffix());
        Long pid = insertOnSaleProduct("甜品");
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + pid + ",\"quantity\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productName").value("甜品"))
                .andExpect(jsonPath("$.data[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].subtotal").value(40.0))
                .andExpect(jsonPath("$.data[0].offSale").value(false));
    }

    /**
     * 场景：加入已下架商品 → 409。
     */
    @Test
    void add_offsale_product_fails() throws Exception {
        String token = registerAndLogin("ce" + uniqueSuffix());
        Product product = new Product();
        product.setShopId(1L);
        product.setCategoryId(1L);
        product.setProductName("下架商品");
        product.setPrice(new BigDecimal("20.00"));
        product.setStock(100);
        product.setSales(0);
        product.setStatus(0); // 下架
        productMapper.insert(product);
        mockMvc.perform(post("/api/cart/items").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + ",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

}