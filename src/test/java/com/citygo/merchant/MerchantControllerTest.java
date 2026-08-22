package com.citygo.merchant;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.mapper.MerchantMapper;
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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家注册接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantMapper merchantMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueUsername() {
        return "mer" + (System.currentTimeMillis() % 1_000_000);
    }

    private String merchantJson(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"123456\","
                + "\"merchantName\":\"测试餐厅\",\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}";
    }

    /**
     * 场景：成功注册商家。断言 code=200、roles 含 MERCHANT、merchant 表有记录。
     */
    @Test
    void register_merchant_success() throws Exception {
        String username = uniqueUsername();
        String body = merchantJson(username);
        String resp = mockMvc.perform(post("/api/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.roles", hasItem("MERCHANT")))
                .andReturn().getResponse().getContentAsString();
        // 校验 merchant 表已有记录
        JsonNode user = objectMapper.readTree(resp).path("data");
        Long userId = user.path("id").asLong();
        Merchant merchant = merchantMapper.selectOne(
                Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        org.junit.jupiter.api.Assertions.assertNotNull(merchant, "商家资料应已创建");
        org.junit.jupiter.api.Assertions.assertEquals("测试餐厅", merchant.getMerchantName());
    }

    /**
     * 场景：重复用户名注册商家。断言 code=409。
     */
    @Test
    void register_duplicate_username() throws Exception {
        String username = uniqueUsername();
        String body = merchantJson(username);
        mockMvc.perform(post("/api/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));
    }

    /**
     * 场景：用户名长度不足 4 位触发参数校验。断言 code=400。
     */
    @Test
    void register_invalid_param() throws Exception {
        String body = "{\"username\":\"ab\",\"password\":\"123456\","
                + "\"merchantName\":\"测试餐厅\",\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}";
        mockMvc.perform(post("/api/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

}