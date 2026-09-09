package com.citygo.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 主配置类。
 *
 * <h3>认证模型：JWT + Redis 无状态</h3>
 * 本工程登录接口内部自行用 {@link PasswordEncoder} 校验密码（不走 Spring Security 自带的
 * {@code DaoAuthenticationProvider}），因此<b>没有引入 {@code UserDetailsService}，也没有表单登录</b>。
 * Spring Security 在这里只负责一件事："请求是否携带经过 {@code JwtAuthenticationFilter} 验证的有效 token"。
 * 这是 JWT + Redis 模式下常见的简化设计——认证逻辑下沉到业务层与自定义过滤器，框架只做"守门员"。
 *
 * <h3>401 / 403 的返回方式</h3>
 * 未认证访问受保护资源（401）或无权限（403）时，由 {@code authenticationEntryPoint} /
 * {@code accessDeniedHandler} 在<b>框架层（Servlet 过滤器阶段）</b>直接写出 HTTP 状态码 + JSON，
 * <b>不经过</b> {@code GlobalExceptionHandler}（那属于 MVC/Controller 层，此时请求还没到 Controller）。
 * 返回的 JSON 为固定结构 {code, message, data}，且<b>手动字符串拼装</b>，不依赖 Jackson 的
 * {@code ObjectMapper} bean——因为 Security 初始化早于 MVC 消息转换器，过早依赖可能触发初始化顺序问题。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 构建安全过滤链（Spring Security 的核心 Bean）。
     * 该方法定义：CSRF/会话策略、URL 放行规则、异常处理器、以及自定义 JWT 过滤器的插入位置。
     *
     * @param http                    HttpSecurity 构建器
     * @param jwtAuthenticationFilter 自定义 JWT 认证过滤器（由 Spring 自动注入）
     * @return 构建好的 SecurityFilterChain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // JWT 把身份放在请求头的 token 里，不使用浏览器 Cookie/Session，
                // 因此没有 CSRF（跨站请求伪造）风险，直接关闭 CSRF 防护。
                .csrf(csrf -> csrf.disable())
                // 无状态会话：Spring Security 不创建也不读取 HttpSession，
                // 每次请求的身份都从 token 重新解析。这是 JWT 模式的硬性要求。
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ==================== 规则顺序原则：精确优先于通配 ====================
                        // authorizeHttpRequests 按"声明顺序"逐条匹配，第一条命中即生效、后续不再判断。
                        // 因此所有精确路径（如 /api/shops/my）都必须排在通配（/api/shops/**）之前，
                        // 否则会被通配规则提前"误放行"。
                        //
                        // 典型反例：/api/shops/my、/api/products/my 是 GET 请求，若不提前声明，
                        // 会被下方的 GET /api/shops/**、GET /api/products/** 通配直接放行，
                        // 导致"我的店铺/我的商品"这类需登录的接口被绕过鉴权。
                        // 所以先显式声明这两个"我的"接口必须 authenticated()，再放行公开浏览的通配。
                        .requestMatchers(HttpMethod.GET, "/api/shops/my", "/api/products/my").authenticated()
                        // 注册、登录公开：任何人都能调用（登录后才能拿到 token）。
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // 商家入驻公开：独立注册入口，与 /api/auth/register 并列。
                        .requestMatchers("/api/merchants/register").permitAll()
                        // 分类列表公开查询：仅放行 GET。限定 HTTP 方法可防止未来该模块新增写接口时
                        // 被这条规则"静默放行"，属于最小权限原则下的防御性写法。
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        // 可领券列表公开查询：仅 GET /api/coupons 放行。
                        // 注意 /api/coupons/my（我的券）不在该精确路径内，仍走末行 anyRequest 要求登录。
                        .requestMatchers(HttpMethod.GET, "/api/coupons").permitAll()
                        // 全文搜索（店铺/商品）公开：前端浏览端无需登录即可检索。
                        .requestMatchers("/api/search/**").permitAll()
                        // 用户端浏览型读接口公开：店铺详情列表、商品列表/详情等。
                        // 仅放行 GET，写操作（POST/PUT/PATCH/DELETE）不匹配此条，仍受末行 anyRequest 约束。
                        .requestMatchers(HttpMethod.GET, "/api/shops/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        // 连通性探测：用于健康检查/前端探活，无需登录。
                        .requestMatchers("/api/ping").permitAll()
                        // Swagger / OpenAPI 文档：未登录也可浏览，方便联调。
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 监控端点（actuator）：仅暴露 health/info，此处一并放行。
                        .requestMatchers("/actuator/**").permitAll()
                        // 预检请求（CORS 的 OPTIONS）：浏览器跨域预检必须放行，否则正常请求会被拦截。
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 兜底：其余所有请求一律要求已认证。
                        // 覆盖 /api/auth/logout、/api/users/me、以及上述所有 GET 通配之外的写操作。
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        // 未认证入口点：已认证检查失败（无 token / token 非法或已过期）时触发。
                        // 返回 401 + 自定义 JSON（与全局统一返回结构对齐）。
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(toJson(401, "未登录或登录已过期"));
                        })
                        // 无权限处理器：已认证但角色不足（如普通用户访问商家接口）时触发。
                        // 返回 403 + 自定义 JSON。
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(toJson(403, "没有操作权限"));
                        }))
                // 将自定义 JWT 过滤器插入到 UsernamePasswordAuthenticationFilter 之前，
                // 保证每个请求先经 JwtAuthenticationFilter 解析 token 并写入 SecurityContext，
                // 后续框架的授权判断才能感知到已登录身份。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码编码器：BCrypt，强度（代价因子）10。
     * Spring Security 用它做密码的加密与匹配（matches(raw, hashed)）。
     * 同一明文每次加密结果不同（自带随机盐），安全性高，无需自行管理盐值。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 关闭 JWT 过滤器的 Boot 自动注册（去重）。
     * {@code JwtAuthenticationFilter} 标注了 {@code @Component}，Boot 会默认把它注册成一个
     * 普通的 Servlet 过滤器（对所有请求生效）；同时我们又在上面的过滤链里
     * addFilterBefore 把它纳入 Security 过滤链。若不关闭自动注册，该过滤器会<b>被执行两次</b>。
     * 这里把它包进 {@code FilterRegistrationBean} 并 setEnabled(false)，
     * 仅由 SecurityFilterChain 管理其执行，避免重复过滤。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 手动拼装统一返回结构的 JSON 字符串。
     * 固定为 {"code":x,"message":"...","data":null}，与 Result&lt;T&gt; 结构对齐，
     * 但此处刻意不用 Jackson Serializer，直接字符串拼接，避免依赖 MVC 层的 ObjectMapper 初始化顺序。
     *
     * @param code    业务码（401 / 403）
     * @param message 提示文案
     * @return JSON 字符串
     */
    private String toJson(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
    }

}
