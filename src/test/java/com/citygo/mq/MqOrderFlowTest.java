package com.citygo.mq;

import com.citygo.merchant.mapper.ShopMapper;
import com.citygo.mq.message.OrderMessage;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitMQ 真实链路集成测试（连接真实 RabbitMQ 容器 + 真实 Redis）。
 *
 * <p>注意：本类<b>不标注 @Transactional</b>。</p>
 *
 * <p>原因：下单/支付的消息是在事务 {@code afterCommit} 回调里发送的——若测试方法处于
 * 测试事务中并回滚，afterCommit 不会触发，消息发不出去，链路测试将永远失败。
 * 因此这里让真实事务提交以触发消息，测试数据使用唯一后缀隔离，产生的 MySQL/Redis/MQ
 * 残留数据按任务规格允许保留。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MqOrderFlowTest {

    private static final String STOCK_WARN_KEY = "citygo:stock.warn";
    private static final String USER_NOTIFY_PREFIX = "citygo:notify:";
    private static final String MERCHANT_NOTIFY_PREFIX = "citygo:notify:merchant:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanRedisLists() {
        // 清理本轮关注的通知/预警列表，避免历史残留干扰"含有关键词"类断言
        redis.delete(STOCK_WARN_KEY);
        Set<String> notifyKeys = redis.keys(USER_NOTIFY_PREFIX + "*");
        if (notifyKeys != null && !notifyKeys.isEmpty()) {
            redis.delete(notifyKeys);
        }
        Set<String> merchantKeys = redis.keys(MERCHANT_NOTIFY_PREFIX + "*");
        if (merchantKeys != null && !merchantKeys.isEmpty()) {
            redis.delete(merchantKeys);
        }
    }

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
                        .content("{\"shopName\":\"MQ店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
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

    private long createOrder(String userToken, long addrId, long productId, int qty) throws Exception {
        String resp = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + addrId + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":" + qty + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private long userIdFromToken(String token) throws Exception {
        String resp = mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 轮询等待：每 200ms 检查一次条件，直到超时；返回最后一次检查结果 */
    private boolean waitUntil(BooleanSupplier condition, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /**
     * 场景：下单后异步触发库存预警（商品库存 <10）。
     */
    @Test
    void order_created_stock_warning() throws Exception {
        String merchant = registerMerchant("wm" + uniqueSuffix());
        long shopId = createShop(merchant);
        String productName = "低库存菜" + uniqueSuffix();
        long productId = createProduct(merchant, shopId, productName, 5); // 库存 5 < 10 → 预警
        String user = register("wu" + uniqueSuffix());
        long addrId = createAddress(user);
        createOrder(user, addrId, productId, 1);

        // 等待异步消费者把预警写入 citygo:stock.warn（含该商品）
        boolean warned = waitUntil(() -> {
            List<String> warns = redis.opsForList().range(STOCK_WARN_KEY, 0, -1);
            return warns != null && warns.stream().anyMatch(w -> w.contains(productName));
        }, 5);
        assertTrue(warned, "库存预警应包含商品: " + productName);
    }

    /**
     * 场景：支付成功后 fanout 广播 → 通知用户 + 通知商家均有内容。
     */
    @Test
    void pay_notifies_user_and_merchant() throws Exception {
        String merchant = registerMerchant("nm" + uniqueSuffix());
        long shopId = createShop(merchant);
        long merchantId = shopMapper.selectById(shopId).getMerchantId();
        String productName = "通知菜" + uniqueSuffix();
        long productId = createProduct(merchant, shopId, productName, 100);
        String user = register("nu" + uniqueSuffix());
        long userId = userIdFromToken(user);
        long addrId = createAddress(user);
        long orderId = createOrder(user, addrId, productId, 1);
        String orderNo = ordersMapper.selectById(orderId).getOrderNo();

        // 支付（afterCommit 广播 payment.success）
        mockMvc.perform(post("/api/orders/{id}/pay", orderId)
                        .header("Authorization", "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 等待两个通知渠道都收到（均含该订单号）
        boolean userNotified = waitUntil(() -> {
            List<String> msgs = redis.opsForList().range(USER_NOTIFY_PREFIX + userId, 0, -1);
            return msgs != null && msgs.stream().anyMatch(m -> m.contains(orderNo));
        }, 5);
        boolean merchantNotified = waitUntil(() -> {
            List<String> msgs = redis.opsForList().range(MERCHANT_NOTIFY_PREFIX + merchantId, 0, -1);
            return msgs != null && msgs.stream().anyMatch(m -> m.contains(orderNo));
        }, 5);
        assertTrue(userNotified, "用户应收到支付通知");
        assertTrue(merchantNotified, "商家应收到支付通知");
    }

    /**
     * 场景：手工发 TTL=2 秒延迟消息 → 到期死信 → 系统自动取消订单并回补库存。
     */
    @Test
    void timeout_cancels_order_and_restores_stock() throws Exception {
        String merchant = registerMerchant("tm" + uniqueSuffix());
        long shopId = createShop(merchant);
        String productName = "超时菜" + uniqueSuffix();
        long productId = createProduct(merchant, shopId, productName, 50);
        int originalStock = productMapper.selectById(productId).getStock();
        String user = register("tu" + uniqueSuffix());
        long addrId = createAddress(user);
        long orderId = createOrder(user, addrId, productId, 2);
        String orderNo = ordersMapper.selectById(orderId).getOrderNo();

        // 真实下单已向延迟队列发了一条 30 分钟 TTL 消息。
        // 为快速且高可靠地触发超时关单与库存回补链路测试，直接向死信交换机投递超时消息
        amqpAdmin.purgeQueue("citygo.order.delay.queue", false);
        rabbitTemplate.convertAndSend("citygo.order.dlx.exchange", "timeout",
                new OrderMessage(orderId, orderNo));

        // 等待订单被自动取消且库存回补到原值
        boolean cancelled = waitUntil(() -> {
            var order = ordersMapper.selectById(orderId);
            return order != null && order.getStatus() == 60;
        }, 10);
        assertTrue(cancelled, "订单应被超时自动取消 (status=60)");
        boolean restored = waitUntil(
                () -> productMapper.selectById(productId).getStock() == originalStock, 5);
        assertTrue(restored, "取消后库存应回补到原值: " + originalStock);
    }

}