package com.citygo.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.cart.service.CartService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单接口测试（下单/详情/取消/支付/我的订单）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.citygo.product.mapper.ProductMapper productMapper;

    @Autowired
    private com.citygo.order.mapper.PaymentMapper paymentMapper;

    @Autowired
    private com.citygo.merchant.mapper.ShopMapper shopMapper;

    @Autowired
    private com.citygo.order.mapper.OrdersMapper ordersMapper;

    @Autowired
    private CartService cartService;

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

    /** 注册商家，返回 token */
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

    /** 商家 token 建店铺，返回 shopId */
    private long createShop(String merchantToken) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"订单店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 商家建商品，返回 productId，stock 可指定 */
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

    /** 用户 token 建地址，返回 addressId */
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
     * 场景：成功下单。断言状态 10、含明细、库存已扣、店铺月销已加。
     */
    @Test
    void create_order_success() throws Exception {
        String merchant = registerMerchant("om" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "招牌菜", 50);
        long beforeStock = productMapper.selectById(productId).getStock();
        long beforeSales = shopMapper.selectById(shopId).getMonthlySales();

        String user = register("ou" + uniqueSuffix());
        long addrId = createAddress(user);
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"remark\":\"不加辣\","
                                + "\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(10))
                .andExpect(jsonPath("$.data.statusDesc").value("待支付"))
                .andExpect(jsonPath("$.data.items[0].productName").value("招牌菜"))
                .andExpect(jsonPath("$.data.payAmount").value(40.0))
                .andReturn().getResponse().getContentAsString();

        // 校验库存已扣 48、店铺月销已 +2
        org.junit.jupiter.api.Assertions.assertEquals(beforeStock - 2, productMapper.selectById(productId).getStock().longValue());
        org.junit.jupiter.api.Assertions.assertEquals(beforeSales + 2, shopMapper.selectById(shopId).getMonthlySales().longValue());
        // 备注快照已在订单
        JsonNode data = objectMapper.readTree(resp).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("不加辣", data.path("remark").asText());
    }

    /**
     * 场景：库存不足下单 → 409，库存不变。
     */
    @Test
    void create_order_insufficient_stock() throws Exception {
        String merchant = registerMerchant("sm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "限量菜", 3);
        String user = register("su" + uniqueSuffix());
        long addrId = createAddress(user);
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":5}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("库存不足")));
        // 库存未变
        org.junit.jupiter.api.Assertions.assertEquals(3, productMapper.selectById(productId).getStock());
    }

    /**
     * 场景：下架商品下单 → 409。
     */
    @Test
    void create_order_offsale_product() throws Exception {
        String merchant = registerMerchant("so" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "下架菜", 10);
        com.citygo.product.entity.Product p = productMapper.selectById(productId);
        p.setStatus(0);
        productMapper.updateById(p);
        String user = register("uc" + uniqueSuffix());
        long addrId = createAddress(user);
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    /**
     * 场景：跨店铺下单 → 400。
     */
    @Test
    void create_order_cross_shop() throws Exception {
        String merchant = registerMerchant("cm" + uniqueSuffix());
        long shop1 = createShop(merchant);
        long shop2 = createShop(merchant);
        long p1 = createProduct(merchant, shop1, "菜A", 10);
        long p2 = createProduct(merchant, shop2, "菜B", 10);
        String user = register("cu" + uniqueSuffix());
        long addrId = createAddress(user);
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":["
                                + "{\"productId\":" + p1 + ",\"quantity\":1},"
                                + "{\"productId\":" + p2 + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 场景：用他人地址下单 → 403。
     */
    @Test
    void create_order_other_user_address() throws Exception {
        String merchant = registerMerchant("am" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "招牌菜", 10);
        String userA = register("au" + uniqueSuffix());
        String userB = register("bu" + uniqueSuffix());
        long addrA = createAddress(userA);
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrA + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /**
     * 场景：下单后购物车中对应商品被清除。
     */
    @Test
    void create_order_clears_cart() throws Exception {
        String merchant = registerMerchant("qm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "招牌菜", 10);
        String user = register("qu" + uniqueSuffix());
        long userId = userIdFromToken(mockMvc, user);
        // 先加购
        cartService.addItem(userId, productId, 2);
        long addrId = createAddress(user);
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 购物车应已清空该商品
        boolean exists = cartService.viewCart(userId).stream()
                .anyMatch(v -> v.getProductId().equals(productId));
        org.junit.jupiter.api.Assertions.assertFalse(exists, "下单后购物车条目应被清除");
    }

    private long userIdFromToken(MockMvc mvc, String token) throws Exception {
        String resp = mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * 场景：订单详情（含明细）。
     */
    @Test
    void order_detail_success() throws Exception {
        long orderId = createOrderReturnId();
        String userToken = lastUserToken;
        mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /**
     * 场景：他人查看我的订单 → 404 防探测。
     */
    @Test
    void order_detail_other_user_not_found() throws Exception {
        long orderId = createOrderReturnId();
        String other = register("other" + uniqueSuffix());
        mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 场景：取消待支付订单 → 状态 60 + 库存回补 + 月销回减。
     * 说明：createOrderReturnId 已扣库存 2、加月销 2；取消后应反向回补。
     */
    @Test
    void cancel_pending_order() throws Exception {
        long orderId = createOrderReturnId();
        // 下单后快照（库存已被扣 2、月销已加 2）
        long afterCreateStock = productMapper.selectById(singleProductId).getStock();
        long afterCreateSales = shopMapper.selectById(singleShopId).getMonthlySales();
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        com.citygo.order.entity.Orders o = ordersMapper.selectById(orderId);
        org.junit.jupiter.api.Assertions.assertEquals(60, o.getStatus());
        // 取消后库存/月销回补到下单前（各恢复 +2）
        org.junit.jupiter.api.Assertions.assertEquals(afterCreateStock + 2, productMapper.selectById(singleProductId).getStock().longValue());
        org.junit.jupiter.api.Assertions.assertEquals(afterCreateSales - 2, shopMapper.selectById(singleShopId).getMonthlySales().longValue());
    }

    /**
     * 场景：先支付再取消 → 409。
     */
    @Test
    void cancel_paid_order_fails() throws Exception {
        long orderId = createOrderReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    /**
     * 场景：支付成功 → 状态 20、payment 记录存在。
     */
    @Test
    void pay_order_success() throws Exception {
        long orderId = createOrderReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        com.citygo.order.entity.Orders o = ordersMapper.selectById(orderId);
        org.junit.jupiter.api.Assertions.assertEquals(20, o.getStatus());
        long cnt = paymentMapper.selectCount(
                Wrappers.<com.citygo.order.entity.Payment>lambdaQuery()
                        .eq(com.citygo.order.entity.Payment::getOrderId, orderId));
        org.junit.jupiter.api.Assertions.assertEquals(1, cnt);
    }

    /**
     * 场景：支付二次幂等 → 仍 200，payment 只有一条。
     */
    @Test
    void pay_order_twice_idempotent() throws Exception {
        long orderId = createOrderReturnId();
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + orderId + "/pay")
                        .header("Authorization", "Bearer " + lastUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        long cnt = paymentMapper.selectCount(
                Wrappers.<com.citygo.order.entity.Payment>lambdaQuery()
                        .eq(com.citygo.order.entity.Payment::getOrderId, orderId));
        org.junit.jupiter.api.Assertions.assertEquals(1, cnt);
    }

    /**
     * 场景：我的订单分页 + 状态筛选（同一用户下两单）。
     */
    @Test
    void my_orders_page() throws Exception {
        String merchant = registerMerchant("pp" + uniqueSuffix());
        long shopId = createShop(merchant);
        long productId = createProduct(merchant, shopId, "分页菜", 50);
        String user = register("pu" + uniqueSuffix());
        long addrId = createAddress(user);
        // 同一用户下两单
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/orders")
                            .header("Authorization", "Bearer " + user)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
        mockMvc.perform(get("/api/orders/my")
                        .header("Authorization", "Bearer " + user)
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1));
        // 状态筛选（待支付）
        mockMvc.perform(get("/api/orders/my")
                        .header("Authorization", "Bearer " + user)
                        .param("status", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    // ---------------------------------------------------------
    // 状态字段 / 辅助
    // ---------------------------------------------------------
    private Long singleProductId;
    private Long singleShopId;
    private String lastUserToken;

    /** 创建一笔订单并返回订单ID，同时记录 lastUserToken/singleProductId/singleShopId */
    private long createOrderReturnId() throws Exception {
        String merchant = registerMerchant("z" + uniqueSuffix());
        singleShopId = createShop(merchant);
        singleProductId = createProduct(merchant, singleShopId, "核心菜", 50);
        lastUserToken = register("u" + uniqueSuffix());
        long addrId = createAddress(lastUserToken);
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + lastUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + singleProductId + ",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

}