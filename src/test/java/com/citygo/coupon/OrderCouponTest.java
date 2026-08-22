package com.citygo.coupon;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.coupon.entity.Coupon;
import com.citygo.coupon.entity.UserCoupon;
import com.citygo.coupon.mapper.CouponMapper;
import com.citygo.coupon.mapper.UserCouponMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 下单用券与取消退券集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderCouponTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserCouponMapper userCouponMapper;

    @Autowired
    private CouponMapper couponMapper;

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
                        .content("{\"shopName\":\"用券店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private long createProduct(String merchantToken, long shopId, String name, int price) throws Exception {
        String resp = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + shopId + ",\"categoryId\":1,"
                                + "\"productName\":\"" + name + "\",\"price\":" + price + ",\"stock\":100}"))
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

    private long claim(long couponId, String userToken) throws Exception {
        String resp = mockMvc.perform(post("/api/coupons/" + couponId + "/claim")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 商家创建商家券，返回券模板 id（scope=2 shopId=绑定的店铺） */
    private long createMerchantCoupon(String merchantToken, long shopId) throws Exception {
        String resp = mockMvc.perform(post("/api/coupons")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponName\":\"商家满减券" + uniqueSuffix() + "\",\"type\":1,"
                                + "\"thresholdAmount\":0.00,\"discountAmount\":10.00,"
                                + "\"totalCount\":10,\"perUserLimit\":1,"
                                + "\"validStart\":\"2020-01-01T00:00:00\",\"validEnd\":\"2099-12-31T23:59:59\","
                                + "\"scope\":2,\"shopId\":" + shopId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private String orderBody(long addrId, long productId, int qty, Long couponId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"addressId\":").append(addrId)
                .append(",\"remark\":\"用券\",\"items\":[{\"productId\":").append(productId)
                .append(",\"quantity\":").append(qty).append("}]");
        if (couponId != null) {
            sb.append(",\"couponId\":").append(couponId);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 场景：使用平台满减券（满 100 减 20），totalAmount=120 → payAmount=100，券核销为 status=2 且绑定 order_id。
     */
    @Test
    void order_with_full_reduction() throws Exception {
        String merchant = registerMerchant("fm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "满减菜", 120); // 单价 120 → 总额 120
        String user = register("fu" + uniqueSuffix());
        long addrId = createAddress(user);
        long userCouponId = claim(1L, user); // 领取平台满减券 id=1

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalAmount").value(120.0))
                .andExpect(jsonPath("$.data.discountAmount").value(20.0))
                .andExpect(jsonPath("$.data.payAmount").value(100.0))
                .andExpect(jsonPath("$.data.couponName").value("平台满减券"));

        UserCoupon uc = userCouponMapper.selectById(userCouponId);
        assertEquals(2, uc.getStatus(), "用券后应为已使用");
        assertNotNull(uc.getOrderId(), "核销应绑定订单");
        assertNotNull(uc.getUsedTime());
    }

    /**
     * 场景：总额未达门槛 → 409。
     */
    @Test
    void order_threshold_not_met() throws Exception {
        String merchant = registerMerchant("tm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "门槛菜", 80); // 总额 80 < 100
        String user = register("tu" + uniqueSuffix());
        long addrId = createAddress(user);
        long userCouponId = claim(1L, user); // 平台满减券门槛 100

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("未满足优惠券使用门槛"));
        // 券未被核销
        assertEquals(1, userCouponMapper.selectById(userCouponId).getStatus());
    }

    /**
     * 场景：用他人券 → 409。
     */
    @Test
    void order_other_user_coupon() throws Exception {
        String merchant = registerMerchant("om" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "他人券菜", 120);
        String userA = register("a" + uniqueSuffix());
        String userB = register("b" + uniqueSuffix());
        long addrId = createAddress(userB);
        long userCouponId = claim(1L, userA); // A 的券

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券不可用"));
    }

    /**
     * 场景：商家券在错误店铺下单 → 409 不适用。
     */
    @Test
    void order_merchant_coupon_wrong_shop() throws Exception {
        String merchant = registerMerchant("sm" + uniqueSuffix());
        long shop1 = createShop(merchant);
        long shop2 = createShop(merchant);
        long couponId = createMerchantCoupon(merchant, shop1); // 券限定 shop1
        long product2 = createProduct(merchant, shop2, "跨店菜", 80); // 在 shop2 下单
        String user = register("su" + uniqueSuffix());
        long addrId = createAddress(user);
        long userCouponId = claim(couponId, user);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, product2, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券不适用于该店铺"));
    }

    /**
     * 场景：已核销券再次下单 → 409。
     */
    @Test
    void order_used_coupon_again() throws Exception {
        String merchant = registerMerchant("um" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "重复用券菜", 120);
        String user = register("uu" + uniqueSuffix());
        long addrId = createAddress(user);
        long userCouponId = claim(1L, user);

        // 第一单成功用券
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 第二单同一张券 → 409（status 已非 1）
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("优惠券不可用"));
    }

    /**
     * 场景：下单用券 → 取消订单 → 券退回 status=1、order_id 为空 → 可再次下单成功。
     */
    @Test
    void cancel_order_returns_coupon() throws Exception {
        String merchant = registerMerchant("rm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "退券菜", 120);
        String user = register("ru" + uniqueSuffix());
        long addrId = createAddress(user);
        long userCouponId = claim(1L, user);

        String orderResp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(orderResp).path("data").path("id").asLong();

        // 取消订单 → 退券
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        UserCoupon uc = userCouponMapper.selectById(userCouponId);
        assertEquals(1, uc.getStatus(), "取消后券应退回未使用");
        assertNull(uc.getOrderId(), "取消后 order_id 应清空");
        assertNull(uc.getUsedTime(), "取消后 used_time 应清空");

        // 券可再次使用
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(addrId, productId, 1, userCouponId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.payAmount").value(100.0));
    }

}