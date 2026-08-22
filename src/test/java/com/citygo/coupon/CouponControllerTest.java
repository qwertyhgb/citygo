package com.citygo.coupon;

import com.citygo.coupon.entity.UserCoupon;
import com.citygo.coupon.mapper.UserCouponMapper;
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

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 优惠券接口测试（商家创建 / 公开列表 / 领券防重防超发 / 我的券）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserCouponMapper userCouponMapper;

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

    private String registerMerchant(String username) throws Exception {
        mockMvc.perform(post("/api/merchants/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\","
                                + "\"merchantName\":\"券商家" + uniqueSuffix() + "\","
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
                        .content("{\"shopName\":\"券店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private String merchantCouponBody(long shopId, int totalCount) {
        return "{\"couponName\":\"商家满减券" + uniqueSuffix() + "\",\"type\":1,"
                + "\"thresholdAmount\":50.00,\"discountAmount\":10.00,"
                + "\"totalCount\":" + totalCount + ",\"perUserLimit\":1,"
                + "\"validStart\":\"2020-01-01T00:00:00\",\"validEnd\":\"2099-12-31T23:59:59\","
                + "\"scope\":2,\"shopId\":" + shopId + "}";
    }

    /**
     * 场景：商家成功创建满减券（scope=2 商家券）。
     */
    @Test
    void create_merchant_coupon_success() throws Exception {
        String merchant = registerMerchant("cm" + uniqueSuffix());
        long shopId = createShop(merchant);
        mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantCouponBody(shopId, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.couponName").isNotEmpty())
                .andExpect(jsonPath("$.data.scope").value(2))
                .andExpect(jsonPath("$.data.receivedCount").value(0));
    }

    /**
     * 场景：普通用户创建券 → 403。
     */
    @Test
    void create_coupon_normal_user_forbidden() throws Exception {
        String user = register("cu" + uniqueSuffix());
        // 随便给一个不存在的 shopId 也行，因为角色校验先触发
        mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantCouponBody(1L, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：商家 A 为不属于自己的店铺 B 创建券 → 403。
     */
    @Test
    void create_coupon_other_shop_forbidden() throws Exception {
        String merchantA = registerMerchant("oa" + uniqueSuffix());
        String merchantB = registerMerchant("ob" + uniqueSuffix());
        long shopB = createShop(merchantB);
        mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + merchantA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantCouponBody(shopB, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：公开列表含 V4 平台券种子（id=1 平台满减券）。
     */
    @Test
    void list_public_coupons() throws Exception {
        String body = mockMvc.perform(get("/api/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        JsonNode records = objectMapper.readTree(body).path("data");
        // 平台满减券 id=1 应出现在公开列表（种子券在有效期内）
        boolean found = false;
        for (JsonNode r : records) {
            if (r.path("id").asLong() == 1L) {
                found = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "公开列表应包含平台满减券 id=1");
    }

    /**
     * 场景：领 V4 平台满减券成功。
     */
    @Test
    void claim_success() throws Exception {
        String user = register("cs" + uniqueSuffix());
        mockMvc.perform(post("/api/coupons/1/claim")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.couponId").value(1))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    /**
     * 场景：重复领同一张券 → 409。
     */
    @Test
    void claim_duplicate() throws Exception {
        String user = register("cd" + uniqueSuffix());
        mockMvc.perform(post("/api/coupons/1/claim").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/coupons/1/claim").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("您已领取过该优惠券"));
    }

    /**
     * 场景：total_count=1 的券被第一人领光后，第二人 → 409 已领完。
     */
    @Test
    void claim_exhausted() throws Exception {
        String merchant = registerMerchant("em" + uniqueSuffix());
        long shopId = createShop(merchant);
        String createResp = mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merchantCouponBody(shopId, 1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long couponId = objectMapper.readTree(createResp).path("data").path("id").asLong();

        String user1 = register("e1" + uniqueSuffix());
        mockMvc.perform(post("/api/coupons/" + couponId + "/claim").header("Authorization", "Bearer " + user1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        String user2 = register("e2" + uniqueSuffix());
        mockMvc.perform(post("/api/coupons/" + couponId + "/claim").header("Authorization", "Bearer " + user2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券已被领完"));
    }

    /**
     * 场景：我的券分页；已过期未使用的券被惰性标记为 status=3。
     */
    @Test
    void my_coupons_page() throws Exception {
        String user = register("mu" + uniqueSuffix());
        String me = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + user))
                .andReturn().getResponse().getContentAsString();
        long userId = objectMapper.readTree(me).path("data").path("id").asLong();

        // 领一张平台满减券（未使用）
        mockMvc.perform(post("/api/coupons/1/claim").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 直接插入一张已过期未使用（expire_time 在过去）的券，验证惰性过期
        UserCoupon expired = new UserCoupon();
        expired.setUserId(userId);
        expired.setCouponId(2L);
        expired.setStatus(1);
        expired.setReceivedTime(LocalDateTime.now().minusDays(1));
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        userCouponMapper.insert(expired);

        mockMvc.perform(get("/api/coupons/my").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(2));
        // 过期的那张应被标记为 status=3
        mockMvc.perform(get("/api/coupons/my").header("Authorization", "Bearer " + user).param("status", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value(3));
    }

}