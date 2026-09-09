package com.citygo.review;

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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评价接口测试（发表 / 重复 / 时机 / 权限 / 回复 / 公开列表 / 我的）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShopMapper shopMapper;

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
                                + "\"merchantName\":\"评商家" + uniqueSuffix() + "\","
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
                        .content("{\"shopName\":\"评价店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
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

    private long createOrder(String userToken, long addrId, long productId) throws Exception {
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 把订单推进到已完成（用户支付 → 商家接单/配送/完成） */
    private long completeOrder(String userToken, String merchantToken, long addrId, long productId) throws Exception {
        long orderId = createOrder(userToken, addrId, productId);
        mockMvc.perform(post("/api/orders/" + orderId + "/pay").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        for (String op : new String[]{"/accept", "/deliver", "/complete"}) {
            mockMvc.perform(post("/api/orders/" + orderId + op).header("Authorization", "Bearer " + merchantToken))
                    .andExpect(status().isOk());
        }
        return orderId;
    }

    private long review(long orderId, String userToken, int rating) throws Exception {
        String resp = mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"rating\":" + rating + ",\"content\":\"好吃\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 场景：已完成订单评 5 星后 shop.score=5；另一单评 3 星 → 平均 4.0。
     */
    @Test
    void review_success_updates_shop_score() throws Exception {
        String merchant = registerMerchant("sm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long p1 = createProduct(merchant, shopId, "评分菜1", 50);
        long p2 = createProduct(merchant, shopId, "评分菜2", 50);
        String user = register("su" + uniqueSuffix());
        long addrId = createAddress(user);

        long o1 = completeOrder(user, merchant, addrId, p1);
        review(o1, user, 5);
        assertEquals(0, new BigDecimal("5.0").compareTo(shopMapper.selectById(shopId).getScore()));
        assertEquals(1, shopMapper.selectById(shopId).getReviewCount());

        long o2 = completeOrder(user, merchant, addrId, p2);
        review(o2, user, 3);
        BigDecimal score = shopMapper.selectById(shopId).getScore();
        assertEquals(0, new BigDecimal("4.0").compareTo(score), "两单平均应为 4.0，实际 " + score);
        assertEquals(2, shopMapper.selectById(shopId).getReviewCount());
    }

    /**
     * 场景：待支付订单评价 → 409。
     */
    @Test
    void review_order_not_completed() throws Exception {
        String merchant = registerMerchant("tm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "未完成菜", 50);
        String user = register("tu" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = createOrder(user, addrId, productId); // 未支付未完成

        mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许该操作"));
    }

    /**
     * 场景：用他人订单评价 → 404。
     */
    @Test
    void review_other_user_order() throws Exception {
        String merchant = registerMerchant("om" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "他人单菜", 50);
        String userA = register("a" + uniqueSuffix());
        String userB = register("b" + uniqueSuffix());
        long addrId = createAddress(userA);
        long orderId = completeOrder(userA, merchant, addrId, productId);

        mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：同一订单评价两次 → 第二次 409。
     */
    @Test
    void review_duplicate() throws Exception {
        String merchant = registerMerchant("dm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "重复评菜", 50);
        String user = register("du" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = completeOrder(user, merchant, addrId, productId);

        review(orderId, user, 5);
        mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该订单已评价"));
    }

    /**
     * 场景：rating=6 → 400。
     */
    @Test
    void review_invalid_rating() throws Exception {
        String merchant = registerMerchant("im" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "评分超界菜", 50);
        String user = register("iu" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = completeOrder(user, merchant, addrId, productId);

        mockMvc.perform(post("/api/reviews")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + orderId + ",\"rating\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 场景：店铺评价公开列表（分页 + 含昵称，昵称=用户名）。
     */
    @Test
    void shop_reviews_list_public() throws Exception {
        String merchant = registerMerchant("lm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "公开评菜", 50);
        String username = "lu" + uniqueSuffix();
        String user = register(username);
        long addrId = createAddress(user);
        long orderId = completeOrder(user, merchant, addrId, productId);
        review(orderId, user, 4);

        mockMvc.perform(get("/api/shops/" + shopId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].rating").value(4))
                .andExpect(jsonPath("$.data.records[0].nickname").value(username))
                .andExpect(jsonPath("$.data.records[0].shopName").isNotEmpty());
    }

    /**
     * 场景：商家回复后 merchant_reply/reply_time 有值。
     */
    @Test
    void merchant_reply_success() throws Exception {
        String merchant = registerMerchant("rm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "回复菜", 50);
        String user = register("ru" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = completeOrder(user, merchant, addrId, productId);
        long reviewId = review(orderId, user, 5);

        mockMvc.perform(post("/api/reviews/" + reviewId + "/reply")
                        .header("Authorization", "Bearer " + merchant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"感谢光临！\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.merchantReply").value("感谢光临！"))
                .andExpect(jsonPath("$.data.replyTime").isNotEmpty());
    }

    /**
     * 场景：A 商家回复 B 店评价 → 403。
     */
    @Test
    void merchant_reply_other_shop_forbidden() throws Exception {
        String merchantA = registerMerchant("pa" + uniqueSuffix());
        long shopA = createShop(merchantA);
        String merchantB = registerMerchant("pb" + uniqueSuffix());
        createShop(merchantB);
        long productA = createProduct(merchantA, shopA, "A店菜", 50);
        String user = register("pu" + uniqueSuffix());
        long addrId = createAddress(user);
        // 在 A 店完成并评价
        long orderId = completeOrder(user, merchantA, addrId, productA);
        long reviewId = review(orderId, user, 5);

        // B 商家试图回复 A 店评价 → 403
        mockMvc.perform(post("/api/reviews/" + reviewId + "/reply")
                        .header("Authorization", "Bearer " + merchantB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"这是A店的\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：我的评价分页。
     */
    @Test
    void my_reviews_page() throws Exception {
        String merchant = registerMerchant("mm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "我的评菜", 50);
        String user = register("mu" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = completeOrder(user, merchant, addrId, productId);
        review(orderId, user, 3);

        mockMvc.perform(get("/api/reviews/my").header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].rating").value(3));
    }

}