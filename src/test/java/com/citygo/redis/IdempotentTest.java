package com.citygo.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 防重复提交（@IdempotentSubmit）集成测试。
 *
 * <p>验证同一用户带相同 X-Request-Id 的下单请求只成功一次，第二次返回 409。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdempotentTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

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

    private long createShop(String merchantToken) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"幂等店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

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

    private String register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    private long createAddress(String userToken) throws Exception {
        String resp = mockMvc.perform(post("/api/addresses")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverName\":\"收货人\",\"receiverPhone\":\"13800138000\","
                                + "\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"朝阳区\","
                                + "\"detailAddress\":\"建国路1号\",\"isDefault\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 场景：带相同 X-Request-Id 两次下单，第一次 200、第二次 409。
     */
    @Test
    void repeat_submit_rejected() throws Exception {
        String merchant = registerMerchant("im" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "幂等菜", 50);
        String user = register("iu" + uniqueSuffix());
        long addrId = createAddress(user);
        String body = "{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        String requestId = "req-" + uniqueSuffix();
        // 第一次：成功
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 第二次：相同 requestId → 409 请勿重复提交
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("请勿重复提交"));
    }

    /**
     * 场景：不同 X-Request-Id 两次下单均成功。
     */
    @Test
    void different_request_id_ok() throws Exception {
        String merchant = registerMerchant("dm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "多单菜", 50);
        String user = register("du" + uniqueSuffix());
        long addrId = createAddress(user);
        String body = "{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .header("X-Request-Id", "r1-" + uniqueSuffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .header("X-Request-Id", "r2-" + uniqueSuffix())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

}