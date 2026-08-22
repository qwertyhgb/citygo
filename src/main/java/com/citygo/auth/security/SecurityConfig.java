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
 * Spring Security 配置。
 *
 * <p>本工程采用 JWT + Redis 的无状态登录模式：登录接口内部自行用
 * {@link PasswordEncoder} 校验密码（不走 {@code DaoAuthenticationProvider}），
 * Spring Security 只负责"请求是否携带有效 token"的认证。
 * 这是 JWT + Redis 模式下常见的简化设计，避免引入 UserDetailsService 与表单登录。</p>
 *
 * <p>未认证访问受保护资源时，由入口点（authenticationEntryPoint）直接在框架层
 * 输出 HTTP 401 + JSON（不经过全局异常处理器，因为那属于 MVC 层，这里发生在 Security 层）。
 * 该 JSON 仅含 {code,message,data} 三个固定字段，直接手动拼装，不依赖 Jackson ObjectMapper bean，
 * 避免与框架初始化顺序耦合。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 构建安全过滤链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // JWT 无 Cookie 会话，无需 CSRF 防护
                .csrf(csrf -> csrf.disable())
                // 无状态会话：不创建/使用 HttpSession
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 注册/登录无需认证
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // 商家注册无需认证（与 /api/auth/register 均是公开注册入口）
                        .requestMatchers("/api/merchants/register").permitAll()
                        // 分类列表公开查询
                        .requestMatchers("/api/categories").permitAll()
                        // 连通性探测无需认证
                        .requestMatchers("/api/ping").permitAll()
                        // Swagger / OpenAPI：未登录也可查看接口文档
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 健康检查与基础信息端点
                        .requestMatchers("/actuator/**").permitAll()
                        // 预检请求
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 其余一律要求认证（含 /api/auth/logout、/api/users/me）
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(toJson(401, "未登录或登录已过期"));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(toJson(403, "没有操作权限"));
                        }))
                // JWT 过滤器在用户名密码过滤器之前执行
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码编码器：BCrypt，强度 10。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 关闭 JWT 过滤器的 Boot 自动注册：
     * 该过滤器仅由 {@link SecurityFilterChain} 管理，若再被 Boot 注册为 Servlet 过滤器会执行两次。
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
     * 手动拼装 {code, message, data:null} 结构的 JSON 字符串。
     */
    private String toJson(int code, String message) {
        return "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
    }

}