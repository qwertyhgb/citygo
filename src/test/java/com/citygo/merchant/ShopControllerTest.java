package com.citygo.merchant;

import com.citygo.merchant.entity.Shop;
import com.citygo.merchant.mapper.ShopMapper;
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
 * 店铺管理接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShopControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    /**
     * 注册普通用户并登录，返回 token（无 MERCHANT 角色）。
     */
    private String registerNormalUserAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
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
    private Long createShop(String token, String shopName) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"" + shopName + "\",\"city\":\"北京\","
                                + "\"district\":\"朝阳区\",\"address\":\"建国路 88 号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 场景：商家成功创建店铺。断言 openStatus=1、score=0.0。
     */
    @Test
    void create_shop_success() throws Exception {
        String token = registerMerchantAndLogin("shop" + uniqueSuffix());
        mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"火锅店A\",\"city\":\"北京\","
                                + "\"district\":\"朝阳区\",\"address\":\"建国路 88 号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.openStatus").value(1))
                .andExpect(jsonPath("$.data.score").value(0));
    }

    /**
     * 场景：普通用户 token 创建店铺。断言 code=403（方法级鉴权拒绝，经全局异常处理器返回）。
     */
    @Test
    void create_shop_with_normal_user_forbidden() throws Exception {
        String token = registerNormalUserAndLogin("usr" + uniqueSuffix());
        mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"火锅店A\",\"city\":\"北京\","
                                + "\"district\":\"朝阳区\",\"address\":\"建国路 88 号\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：A 商家 token 改 B 商家的店铺。断言 code=403（越权）。
     */
    @Test
    void update_other_merchant_shop_forbidden() throws Exception {
        String tokenA = registerMerchantAndLogin("ma" + uniqueSuffix());
        String tokenB = registerMerchantAndLogin("mb" + uniqueSuffix());
        Long shopA = createShop(tokenA, "A的店");
        // B 尝试改 A 的店铺
        mockMvc.perform(put("/api/shops/" + shopA)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"B改的店\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：切换营业状态后查库确认。
     */
    @Test
    void toggle_open_status() throws Exception {
        String token = registerMerchantAndLogin("st" + uniqueSuffix());
        Long shopId = createShop(token, "状态店");
        mockMvc.perform(patch("/api/shops/" + shopId + "/open-status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openStatus\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        Shop shop = shopMapper.selectById(shopId);
        org.junit.jupiter.api.Assertions.assertEquals(0, shop.getOpenStatus());
    }

    /**
     * 场景：我的店铺列表。
     */
    @Test
    void my_shops_list() throws Exception {
        String token = registerMerchantAndLogin("sl" + uniqueSuffix());
        createShop(token, "店1");
        createShop(token, "店2");
        mockMvc.perform(get("/api/shops/my").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

}