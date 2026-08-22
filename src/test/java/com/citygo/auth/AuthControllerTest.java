package com.citygo.auth;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证/用户接口集成测试。
 *
 * <p>数据库写入通过 {@code @Transactional} 回滚，测试不会污染 citygo 库；
 * Redis 中写入的登录态 key 会残留，但带 TTL 会自动过期，可接受。</p>
 *
 * <p>说明：业务异常以 HTTP 200 + Result.code 表达（通用返回结构设计）；
 * 而"未认证访问受保护资源"由 Security 入口点直接以 HTTP 401 返回。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 测试用 ObjectMapper：仅用于从响应 JSON 中提取 token。
     * 直接 new 而非注入 bean，避免依赖上下文中的 Jackson ObjectMapper bean。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 生成一个长度 4-20 位、跨运行不重复的用户名（测试回滚故同一轮内可复用） */
    private String uniqueUsername() {
        return "test" + (System.currentTimeMillis() % 1_000_000);
    }

    /** 以 JSON 形式的请求体发起 post 请求 */
    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 注册并登录，返回 token */
    private String registerAndLogin(String username, String password) throws Exception {
        String regBody = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        postJson("/api/auth/register", regBody).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        String loginBody = regBody;
        String loginResp = postJson("/api/auth/login", loginBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResp).path("data").path("token").asText();
    }

    // ---------------- 注册 ----------------

    /**
     * 场景：成功注册新用户。断言 200、code=200、返回 user 不含 password、roles 含 USER。
     */
    @Test
    void register_success() throws Exception {
        String username = uniqueUsername();
        String body = "{\"username\":\"" + username + "\",\"password\":\"123456\"}";
        postJson("/api/auth/register", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.roles", hasItem("USER")));
    }

    /**
     * 场景：重复注册同名用户。断言 code=409、message 含"已存在"。
     */
    @Test
    void register_duplicate_username() throws Exception {
        String username = uniqueUsername();
        String body = "{\"username\":\"" + username + "\",\"password\":\"123456\"}";
        postJson("/api/auth/register", body).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        // 第二次注册同用户名
        postJson("/api/auth/register", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));
    }

    /**
     * 场景：用户名长度不足 4 位触发参数校验。断言 code=400（HTTP 200）。
     */
    @Test
    void register_invalid_param() throws Exception {
        String body = "{\"username\":\"abc\",\"password\":\"123456\"}";
        postJson("/api/auth/register", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------------- 登录 ----------------

    /**
     * 场景：注册后正确密码登录。断言 200、token 非空、user 正确。
     */
    @Test
    void login_success() throws Exception {
        String username = uniqueUsername();
        registerAndLogin(username, "123456");
    }

    /**
     * 场景：错误密码登录。断言 code=401、message 为"用户名或密码错误"。
     */
    @Test
    void login_wrong_password() throws Exception {
        String username = uniqueUsername();
        String regBody = "{\"username\":\"" + username + "\",\"password\":\"123456\"}";
        postJson("/api/auth/register", regBody).andExpect(status().isOk());
        String wrongBody = "{\"username\":\"" + username + "\",\"password\":\"wrongpass\"}";
        postJson("/api/auth/login", wrongBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    // ---------------- 当前用户 ----------------

    /**
     * 场景：带 token 访问 /api/users/me。断言 200、username 正确。
     */
    @Test
    void me_with_token() throws Exception {
        String username = uniqueUsername();
        String token = registerAndLogin(username, "123456");
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username));
    }

    /**
     * 场景：不带 token 访问受保护接口。断言 HTTP 401、body code=401。
     */
    @Test
    void me_without_token() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 场景：登出后再用原 token 访问 /me。断言 401（证明 Redis 登录态已删除）。
     */
    @Test
    void logout_then_me_unauthorized() throws Exception {
        String token = registerAndLogin(uniqueUsername(), "123456");
        // 登出（需认证）
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 复用原 token 访问 me -> 401
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

}