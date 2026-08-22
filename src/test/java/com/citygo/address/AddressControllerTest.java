package com.citygo.address;

import com.citygo.address.entity.Address;
import com.citygo.address.mapper.AddressMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收货地址接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AddressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AddressMapper addressMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    /**
     * 注册用户并登录，返回 token。
     */
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

    private String addressJson(String receiverName, String phone, int isDefault) {
        return "{\"receiverName\":\"" + receiverName + "\",\"receiverPhone\":\"" + phone + "\","
                + "\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"朝阳区\","
                + "\"detailAddress\":\"建国路1号\",\"isDefault\":" + isDefault + "}";
    }

    /**
     * 场景：新增地址成功。
     */
    @Test
    void add_success() throws Exception {
        String token = registerAndLogin("addr" + uniqueSuffix());
        mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addressJson("张三", "13800138000", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.receiverName").value("张三"));
    }

    /**
     * 场景：新增第二个默认地址会取消旧默认，保证同时只有一个默认地址。
     */
    @Test
    void add_default_replaces_old_default() throws Exception {
        String token = registerAndLogin("def" + uniqueSuffix());
        mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("张三", "13800138000", 1)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("李四", "13900139000", 1)))
                .andExpect(status().isOk());
        // 查库确认：该用户只有一个默认地址
        Long userId = objectMapper.readTree(mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();
        long defaults = addressMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Address>lambdaQuery()
                        .eq(Address::getUserId, userId).eq(Address::getIsDefault, 1)).size();
        org.junit.jupiter.api.Assertions.assertEquals(1, defaults);
    }

    /**
     * 场景：我的地址列表。
     */
    @Test
    void list_mine() throws Exception {
        String token = registerAndLogin("lst" + uniqueSuffix());
        mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("张三", "13800138000", 0)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/addresses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    /**
     * 场景：更新他人地址 → 403 越权。
     */
    @Test
    void update_other_user_address_forbidden() throws Exception {
        String tokenA = registerAndLogin("ua" + uniqueSuffix());
        String tokenB = registerAndLogin("ub" + uniqueSuffix());
        String resp = mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("张三", "13800138000", 0)))
                .andReturn().getResponse().getContentAsString();
        long addrId = objectMapper.readTree(resp).path("data").path("id").asLong();
        mockMvc.perform(put("/api/addresses/" + addrId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("李四", "13900139000", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：删除地址成功（逻辑删除）。
     */
    @Test
    void delete_success() throws Exception {
        String token = registerAndLogin("del" + uniqueSuffix());
        String resp = mockMvc.perform(post("/api/addresses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("张三", "13800138000", 0)))
                .andReturn().getResponse().getContentAsString();
        long addrId = objectMapper.readTree(resp).path("data").path("id").asLong();
        mockMvc.perform(delete("/api/addresses/" + addrId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        org.junit.jupiter.api.Assertions.assertNull(addressMapper.selectById(addrId));
    }

}