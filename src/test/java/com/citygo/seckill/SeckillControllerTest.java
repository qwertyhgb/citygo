package com.citygo.seckill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.order.entity.OrderItem;
import com.citygo.order.entity.Orders;
import com.citygo.order.enums.OrderSource;
import com.citygo.order.mapper.OrderItemMapper;
import com.citygo.order.mapper.OrdersMapper;
import com.citygo.product.entity.Product;
import com.citygo.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀场景全链路集成测试。
 *
 * <p><b>秒杀测试清理策略（重要说明）</b>：
 * 秒杀订单是由 MQ 消费者在独立线程与独立事务中异步创建的，测试方法上的事务回滚机制无法覆盖
 * 消费者已提交的异步订单。因此测试中采取两重防护：
 * 1. 所有 {@code waitUntil} 轮询查询均严格按当前测试用户的 {@code userId} 过滤，避免命中历史残留订单；
 * 2. 每次测试前后均通过 {@code @AfterEach} / {@code @BeforeEach} 对秒杀订单、明细及 Redis 键进行清理，
 *    确保测试环境无残留，保证用例独立与可重复执行。
 * </p>
 *
 * <p>覆盖规格：
 * 1. config_seckill_success：商家配置秒杀成功，Redis 预扣库存 key 已初始化；
 * 2. seckill_before_start：未开始抢购 → 409 (SELL_NOT_STARTED)；
 * 3. seckill_success_creates_order_async：秒杀成功 → 轮询等待订单出现（source=2、单价=秒杀价、数量 1）→ Redis 预扣库存 -1；
 * 4. seckill_repeat_user：同一用户再次抢购 → 409 (SELL_REPEAT，Redis 一人一单)；
 * 5. seckill_sold_out：seckillStock=1 时第二人抢购 → 409 (SELL_OUT，预扣到负回滚)；
 * 6. seckill_off_not_configured：未配置秒杀的商品 → 409 (SELL_NOT_AVAILABLE)；
 * 7. seckill_order_cancel_restores_seckill_stock：秒杀订单取消 → seckill_stock 回补 + Redis 预扣 +1 + 缓存失效。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class SeckillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 用户认证信息（Token 与用户ID） */
    public record UserAuth(String token, Long userId) {}

    @BeforeEach
    void setUp() {
        cleanResidue();
    }

    @AfterEach
    void tearDown() {
        cleanResidue();
    }

    /**
     * 清理秒杀订单及 Redis 测试残留数据。
     *
     * <p>为什么必须硬删：消费者异步插入的秒杀订单不在测试主线程事务内，
     * MyBatis-Plus 的 deleteById 会执行软删除 (deleted=1)，因此必须通过 DELETE SQL
     * 进行物理硬删除，避免 orders 表与 Redis 残留脏数据污染后续测试运行。</p>
     */
    private void cleanResidue() {
        // 1. 物理硬删数据库中的秒杀订单及订单明细
        jdbcTemplate.update("DELETE FROM order_item WHERE order_id IN (SELECT id FROM orders WHERE source = 2)");
        jdbcTemplate.update("DELETE FROM orders WHERE source = 2");

        // 2. 清理 Redis 中的秒杀库存、一人一单去重、限流及缓存 key
        Set<String> seckillKeys = redis.keys("citygo:seckill:*");
        if (seckillKeys != null && !seckillKeys.isEmpty()) {
            redis.delete(seckillKeys);
        }
        Set<String> rateKeys = redis.keys("citygo:rate:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redis.delete(rateKeys);
        }
        redis.delete("citygo:cache:hotProducts");
        Set<String> detailKeys = redis.keys("productDetail::*");
        if (detailKeys != null && !detailKeys.isEmpty()) {
            redis.delete(detailKeys);
        }
    }

    private String uniqueSuffix() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000 + (long) (Math.random() * 1000));
    }

    private UserAuth register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(login).path("data");
        String token = data.path("token").asText();
        Long userId = data.path("user").path("id").asLong();
        return new UserAuth(token, userId);
    }

    private UserAuth registerMerchant(String username) throws Exception {
        mockMvc.perform(post("/api/merchants/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\","
                                + "\"merchantName\":\"秒杀餐厅" + uniqueSuffix() + "\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800138000\"}"))
                .andExpect(status().isOk());
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(login).path("data");
        String token = data.path("token").asText();
        Long userId = data.path("user").path("id").asLong();
        return new UserAuth(token, userId);
    }

    private long createShop(String merchantToken) throws Exception {
        String resp = mockMvc.perform(post("/api/shops")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"秒杀店" + uniqueSuffix() + "\",\"city\":\"北京\",\"address\":\"某地\"}"))
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

    private void configSeckill(String merchantToken, long productId, BigDecimal seckillPrice, int seckillStock,
                               LocalDateTime start, LocalDateTime end) throws Exception {
        String payload = "{\"seckillPrice\":" + seckillPrice
                + ",\"seckillStock\":" + seckillStock
                + ",\"startTime\":\"" + start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\""
                + ",\"endTime\":\"" + end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\"}";
        mockMvc.perform(post("/api/products/" + productId + "/seckill")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 轮询等待辅助方法：每 200ms 查一次，最长等待 timeoutMs 毫秒。
     */
    private boolean waitUntil(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(200);
        }
        return Boolean.TRUE.equals(condition.call());
    }

    /**
     * 1. 商家配置秒杀成功，Redis 预扣库存 key 已初始化。
     */
    @Test
    void config_seckill_success() throws Exception {
        UserAuth merchant = registerMerchant("m_cfg_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "秒杀配置商品", 100);

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("9.90"), 10, start, end);

        Product p = productMapper.selectById(productId);
        assertNotNull(p.getSeckillPrice());
        assertEquals(new BigDecimal("9.90"), p.getSeckillPrice());
        assertEquals(10, p.getSeckillStock());
        assertNotNull(p.getSeckillStart());
        assertNotNull(p.getSeckillEnd());

        String stockInRedis = redis.opsForValue().get("citygo:seckill:stock:" + productId);
        assertEquals("10", stockInRedis);
    }

    /**
     * 2. 未开始抢购 → 409 (SELL_NOT_STARTED)。
     */
    @Test
    void seckill_before_start() throws Exception {
        UserAuth merchant = registerMerchant("m_bs_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "未开始秒杀商品", 100);

        LocalDateTime start = LocalDateTime.now().plusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("5.00"), 10, start, end);

        UserAuth user = register("u_bs_" + uniqueSuffix());
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("秒杀未开始"));
    }

    /**
     * 3. 秒杀成功 → 轮询等待订单出现（按 userId 过滤、source=2、单价=秒杀价、数量 1）→ Redis 预扣库存 -1。
     */
    @Test
    void seckill_success_creates_order_async() throws Exception {
        UserAuth merchant = registerMerchant("m_suc_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "秒杀成功商品", 100);

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("6.60"), 5, start, end);

        UserAuth user = register("u_suc_" + uniqueSuffix());
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("抢购成功，订单创建中"));

        // Redis 预扣库存立即 -1 (5 -> 4)
        String stockInRedis = redis.opsForValue().get("citygo:seckill:stock:" + productId);
        assertEquals("4", stockInRedis);

        // 轮询等待异步订单创建（最长 10 秒），按当前测试用户 ID 过滤
        boolean created = waitUntil(() -> {
            List<Orders> orders = ordersMapper.selectList(
                    Wrappers.<Orders>lambdaQuery()
                            .eq(Orders::getUserId, user.userId())
                            .eq(Orders::getSource, OrderSource.SECKILL.getCode())
                            .eq(Orders::getPayAmount, new BigDecimal("6.60")));
            if (orders.isEmpty()) {
                return false;
            }
            Orders order = orders.get(orders.size() - 1);
            List<OrderItem> items = orderItemMapper.selectList(
                    Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()));
            return !items.isEmpty() && items.get(0).getProductId().equals(productId)
                    && items.get(0).getQuantity() == 1
                    && items.get(0).getPrice().compareTo(new BigDecimal("6.60")) == 0;
        }, 10000);

        assertTrue(created, "秒杀异步订单应成功创建并落库");
    }

    /**
     * 4. 同一用户再次抢购 → 409（Redis 一人一单）。
     */
    @Test
    void seckill_repeat_user() throws Exception {
        UserAuth merchant = registerMerchant("m_rpt_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "一人一单商品", 100);

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("8.00"), 10, start, end);

        UserAuth user = register("u_rpt_" + uniqueSuffix());

        // 第一次抢购成功
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第二次抢购拦截（409 每人限购一件）
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("每人限购一件，请勿重复抢购"));
    }

    /**
     * 5. seckillStock=1 时第二人抢购 → 409（预扣到负回滚）。
     */
    @Test
    void seckill_sold_out() throws Exception {
        UserAuth merchant = registerMerchant("m_so_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "售罄秒杀商品", 100);

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("1.00"), 1, start, end);

        UserAuth user1 = register("u_so1_" + uniqueSuffix());
        UserAuth user2 = register("u_so2_" + uniqueSuffix());

        // 用户1抢购成功
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 用户2抢购拦截（409 已抢完）
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user2.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("手慢了，商品已抢完"));

        // Redis 库存回滚为 0
        String stockInRedis = redis.opsForValue().get("citygo:seckill:stock:" + productId);
        assertEquals("0", stockInRedis);
    }

    /**
     * 6. 未配置秒杀的商品 → 409 (SELL_NOT_AVAILABLE)。
     */
    @Test
    void seckill_off_not_configured() throws Exception {
        UserAuth merchant = registerMerchant("m_off_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "普通未秒杀商品", 100);

        UserAuth user = register("u_off_" + uniqueSuffix());
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该商品未参与秒杀"));
    }

    /**
     * 7. 秒杀订单取消 → seckill_stock 回补 + Redis 预扣 +1 + 缓存失效。
     */
    @Test
    void seckill_order_cancel_restores_seckill_stock() throws Exception {
        UserAuth merchant = registerMerchant("m_can_" + uniqueSuffix());
        long shopId = createShop(merchant.token());
        long productId = createProduct(merchant.token(), shopId, "秒杀取消商品", 100);

        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now().plusHours(2);
        configSeckill(merchant.token(), productId, new BigDecimal("3.50"), 5, start, end);

        UserAuth user = register("u_can_" + uniqueSuffix());
        mockMvc.perform(post("/api/seckill/" + productId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 等待当前测试用户的异步订单创建成功
        final Orders[] holder = new Orders[1];
        boolean created = waitUntil(() -> {
            List<Orders> list = ordersMapper.selectList(
                    Wrappers.<Orders>lambdaQuery()
                            .eq(Orders::getUserId, user.userId())
                            .eq(Orders::getSource, OrderSource.SECKILL.getCode())
                            .eq(Orders::getPayAmount, new BigDecimal("3.50")));
            if (!list.isEmpty()) {
                holder[0] = list.get(list.size() - 1);
                return true;
            }
            return false;
        }, 10000);
        assertTrue(created, "当前用户的秒杀订单应创建成功");

        Orders seckillOrder = holder[0];
        long orderId = seckillOrder.getId();

        // 写入缓存模拟已缓存状态
        redis.opsForValue().set("productDetail::" + productId, "mockDetail");
        redis.opsForValue().set("citygo:cache:hotProducts", "mockHot");

        // 用户取消秒杀订单
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 验证订单状态为 60 已取消
        Orders cancelled = ordersMapper.selectById(orderId);
        assertEquals(60, cancelled.getStatus());

        // 验证 DB seckill_stock 回补到 5
        Product p = productMapper.selectById(productId);
        assertEquals(5, p.getSeckillStock());

        // 验证 Redis 预扣库存从 4 回补到 5
        String stockInRedis = redis.opsForValue().get("citygo:seckill:stock:" + productId);
        assertEquals("5", stockInRedis);

        // 验证缓存已被失效
        assertFalse(redis.hasKey("productDetail::" + productId), "商品详情缓存应被失效");
        assertFalse(redis.hasKey("citygo:cache:hotProducts"), "热门商品缓存应被失效");
    }

}
