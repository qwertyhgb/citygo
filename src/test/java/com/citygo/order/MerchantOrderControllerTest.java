package com.citygo.order;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家订单接口测试（列表 + 状态流转 + 越权）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MerchantOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
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

    private String registerMerchantAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/merchants/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\","
                                + "\"merchantName\":\"商家" + uniqueSuffix() + "\","
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
                        .content("{\"shopName\":\"店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private long createProduct(String merchantToken, long shopId, String name) throws Exception {
        String resp = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":1,"
                                + "\"productName\":\"" + name + "\",\"price\":20.00,\"stock\":50}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
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

    /** 造一笔已支付订单（商家接单前置状态），返回 {merchantToken, orderId} */
    private long createPaidOrderAndReturnId() throws Exception {
        String merchant = registerMerchantAndLogin("ma" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "招牌菜");
        String user = register("mu" + uniqueSuffix());
        long addrId = createAddress(user);
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(resp).path("data").path("id").asLong();
        mockMvc.perform(post("/api/orders/" + orderId + "/pay").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk());
        lastMerchantToken = merchant;
        return orderId;
    }

    private String lastMerchantToken = null;

    /**
     * 场景：商家订单列表。
     */
    @Test
    void merchant_orders_list() throws Exception {
        createPaidOrderAndReturnId();
        mockMvc.perform(get("/api/orders/merchant")
                        .header("Authorization", "Bearer " + lastMerchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    /**
     * 场景：接单成功（20→30）。
     */
    @Test
    void accept_success() throws Exception {
        long orderId = createPaidOrderAndReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/accept")
                        .header("Authorization", "Bearer " + lastMerchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：配送成功（30→40）。
     */
    @Test
    void deliver_success() throws Exception {
        long orderId = createPaidOrderAndReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/accept").header("Authorization", "Bearer " + lastMerchantToken));
        mockMvc.perform(post("/api/orders/" + orderId + "/deliver")
                        .header("Authorization", "Bearer " + lastMerchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：完成成功（40→50 且写 completed_time）。
     */
    @Test
    void complete_success() throws Exception {
        long orderId = createPaidOrderAndReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/accept").header("Authorization", "Bearer " + lastMerchantToken));
        mockMvc.perform(post("/api/orders/" + orderId + "/deliver").header("Authorization", "Bearer " + lastMerchantToken));
        mockMvc.perform(post("/api/orders/" + orderId + "/complete")
                        .header("Authorization", "Bearer " + lastMerchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 场景：别的商家接单 → 403 越权。
     */
    @Test
    void other_merchant_accept_forbidden() throws Exception {
        long orderId = createPaidOrderAndReturnId();
        String otherMerchant = registerMerchantAndLogin("mb" + uniqueSuffix());
        mockMvc.perform(post("/api/orders/" + orderId + "/accept")
                        .header("Authorization", "Bearer " + otherMerchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：待支付订单直接接单 → 409（状态机不允许 10→30）。
     */
    @Test
    void wrong_status_transition_fails() throws Exception {
        // 造一笔未支付订单
        String merchant = registerMerchantAndLogin("mc" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "招牌菜");
        String user = register("mu2" + uniqueSuffix());
        long addrId = createAddress(user);
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(resp).path("data").path("id").asLong();
        // 未支付(10) 直接接单
        mockMvc.perform(post("/api/orders/" + orderId + "/accept")
                        .header("Authorization", "Bearer " + merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

}