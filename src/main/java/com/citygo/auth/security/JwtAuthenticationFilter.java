package com.citygo.auth.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.auth.entity.Role;
import com.citygo.auth.entity.UserRole;
import com.citygo.auth.mapper.RoleMapper;
import com.citygo.auth.mapper.UserRoleMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>在 Spring Security 过滤链中，于 {@code UsernamePasswordAuthenticationFilter} 之前执行：
 * 若请求带有有效 Bearer token（且 Redis 中存在对应登录态），则把用户信息写入
 * {@link SecurityContextHolder}，完成认证；否则放行，由 Security 的入口点对受保护资源统一返回 401。</p>
 *
 * <p>登录态校验同时依赖：① JWT 签名/有效期（JwtUtil 解析）；② Redis 中是否仍保有
 * {@code citygo:login:token:{token} -> userId}。任一不满足都视为未认证。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Redis 登录态 key 前缀 */
    public static final String LOGIN_TOKEN_PREFIX = "citygo:login:token:";

    /** 请求中存放的原始 token 属性 key（供登出接口使用） */
    public static final String REQUEST_ATTR_TOKEN = "citygo_token";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   StringRedisTemplate stringRedisTemplate,
                                   UserRoleMapper userRoleMapper,
                                   RoleMapper roleMapper) {
        this.jwtUtil = jwtUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 取 Authorization 头；非 Bearer 头直接放行（不认证）
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authorization.substring(7);

        // 2. 解析 JWT；非法/过期则放行，由入口点统一返回 401
        Long userId;
        try {
            userId = jwtUtil.parseUserId(token);
        } catch (Exception e) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 校验 Redis 登录态：key 存在且值等于 userId；否则视为已登出，放行（未认证）
        String redisValue = stringRedisTemplate.opsForValue().get(LOGIN_TOKEN_PREFIX + token);
        if (redisValue == null || !redisValue.equals(String.valueOf(userId))) {
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 从数据库加载该用户的角色列表（本阶段简化：每次请求查库；
        //    后续可优化为登录时把角色缓存进 Redis，减少查库）。
        List<SimpleGrantedAuthority> authorities = loadAuthorities(userId);

        // 5. 构建已认证的 Authentication（principal 为 userId），写入上下文，并透传原始 token
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(REQUEST_ATTR_TOKEN, token);

        filterChain.doFilter(request, response);
    }

    /**
     * 查询用户绑定的角色编码，并映射为 Spring Security 的 {@code ROLE_xxx} 权限。
     */
    private List<SimpleGrantedAuthority> loadAuthorities(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectByIds(roleIds).stream()
                .map(Role::getCode)
                .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                .toList();
    }

}