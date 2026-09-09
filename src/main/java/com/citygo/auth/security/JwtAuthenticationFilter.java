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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（Spring Security 自定义过滤器）。
 *
 * <h3>在过滤链中的位置</h3>
 * 本过滤器在 {@code SecurityConfig} 中被注册到 {@code UsernamePasswordAuthenticationFilter}
 * <b>之前</b>执行（见 {@code .addFilterBefore(...)}）。即每个请求先经过它做 JWT 认证，
 * 再进入 Spring Security 原生的用户名密码过滤器（本项目并未启用表单登录）。
 *
 * <h3>职责：只"认证"，不"授权"</h3>
 * <ul>
 *     <li>若请求带<b>合法 Bearer token</b> 且 <b>Redis 中仍有对应登录态</b>，则把用户信息
 *         （userId + 角色权限）写入 {@link SecurityContextHolder}，标记为"已认证"；</li>
 *     <li>否则<b>直接放行</b>（不写认证），让请求继续往后走。真正返回 401 的责任在
 *         {@code SecurityConfig} 的 {@code authenticationEntryPoint}——它会在
 *         "请求的是受保护资源，但当前无认证" 时才吐出 401 JSON。</li>
 * </ul>
 * 因此本过滤器与访问决策解耦：它只负责"认出你是谁"，不决定"你能不能访问"。
 *
 * <h3>双重校验（JWT + Redis 登录态）</h3>
 * 登录态同时依赖两项，任一不满足都视为未认证：
 * <ol>
 *     <li><b>JWT 签名/有效期</b>：由 {@link JwtUtil#parseUserId(String)} 校验（防篡改 + 防过期）；</li>
 *     <li><b>Redis 登录态</b>：{@code citygo:login:token:{token} -> userId:roleCodes} 是否仍存在。
 *         这一层用于支持 JWT 本身做不到的"主动登出 / 改密踢下线 / 管理员强制下线"——
 *         这些场景下只需删除 Redis 中的该 key 即可使未过期的 token 立即失效。</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 日志器。 */
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * Redis 登录态 key 前缀。
     * 完整 key = {@code citygo:login:token:{token}}，value 格式为 {@code userId:roleCodes}。
     * 必须与 {@code AuthServiceImpl} 登录时写入的 key 约定一致。
     */
    public static final String LOGIN_TOKEN_PREFIX = "citygo:login:token:";

    /**
     * 将原始 token 暂存到 request 属性时所用的 key。
     * 下游登出接口可直接从 request 取出 token，无需前端重复传递，从而删除对应 Redis 登录态实现即时失效。
     */
    public static final String REQUEST_ATTR_TOKEN = "citygo_token";

    /** JWT 工具：用于解析并校验 token（签名 + 过期）。 */
    private final JwtUtil jwtUtil;

    /** Redis 模板：用于读取登录态（key -> userId:roleCodes）。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 用户-角色中间表 Mapper：降级查库时查询用户绑定的角色 ID。 */
    private final UserRoleMapper userRoleMapper;

    /** 角色表 Mapper：降级查库时根据角色 ID 查询角色编码（code）。 */
    private final RoleMapper roleMapper;

    /**
     * 构造器：由 Spring 注入全部依赖（均为 final，单例线程安全）。
     *
     * @param jwtUtil                JWT 工具
     * @param stringRedisTemplate    Redis 操作模板
     * @param userRoleMapper         用户-角色中间表 Mapper
     * @param roleMapper             角色表 Mapper
     */
    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   StringRedisTemplate stringRedisTemplate,
                                   UserRoleMapper userRoleMapper,
                                   RoleMapper roleMapper) {
        this.jwtUtil = jwtUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 过滤器核心逻辑：每个请求都会执行一次（继承 {@code OncePerRequestFilter} 保证单次请求只跑一遍）。
     * 主流程分 5 步：① 取 Bearer 头 → ② 解析 JWT → ③ 校验 Redis 登录态 →
     * ④ 加载角色权限 → ⑤ 写入 SecurityContext 并透传 token。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // ============ 第 1 步：取 Authorization 头 ============
        // 约定前端携带 "Authorization: Bearer <token>"。无该头或非 Bearer 方案，
        // 视为游客请求，直接放行（不写入认证）。后续是否允许访问由 SecurityConfig 规则决定。
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        // "Bearer " 恰好 7 个字符（含末尾空格），substring(7) 切掉前缀得到纯 token。
        String token = authorization.substring(7);

        // ============ 第 2 步：解析并校验 JWT ============
        // 交给 JwtUtil：内部完成签名校验（防篡改）+ 过期校验 + 格式校验。
        // 校验失败（非法/过期/被篡改）会抛出 JwtException，被这里 catch。
        Long userId;
        try {
            userId = jwtUtil.parseUserId(token);
        } catch (Exception e) {
            // 非法/过期 token 是常态（过期、瞎填、恶意探测），按未认证放行。
            // 仅记 debug（不打 warn/error、不打堆栈），避免被高频恶意请求刷爆日志。
            log.debug("JWT 解析失败，按未认证处理: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // ============ 第 3 步：校验 Redis 登录态（JWT 之外的第二重保险）============
        // 读取登录时写入的 key：citygo:login:token:{token} -> "userId:roleCodes"。
        String redisValue = stringRedisTemplate.opsForValue().get(LOGIN_TOKEN_PREFIX + token);
        if (redisValue == null) {
            // Redis 中没有该 key = 已登出 / 已过期被清理 → 按未认证放行。
            log.debug("Redis 登录态不存在（已登出或已过期），按未认证处理");
            filterChain.doFilter(request, response);
            return;
        }
        // 按 ':' 最多切成 2 段（limit=2）：parts[0]=userId，parts[1]=roleCodes。
        // 用 limit=2 是防御性写法：即便 roleCodes 未来含 ':' 也不会把 userId 切坏。
        String[] parts = redisValue.split(":", 2);
        // 交叉比对：用 JWT 解析出的 userId 与 Redis 中存的 userId 是否一致，防止二者被拼凑不一致。
        if (!parts[0].equals(String.valueOf(userId))) {
            filterChain.doFilter(request, response);
            return;
        }

        // ============ 第 4 步：加载角色权限 ============
        // 优化点：Redis 登录态里已缓存角色编码（roleCodes），直接解析即可，
        // 避免每个请求都去 user_role / role 两张表查角色（否则高并发下每次请求多 2 次查库）。
        // 若登录态不含角色缓存（老格式/空），则降级走 loadAuthorities 查库。
        List<SimpleGrantedAuthority> authorities;
        if (parts.length > 1 && !parts[1].isBlank()) {
            authorities = java.util.Arrays.stream(parts[1].split(","))
                    .filter(s -> !s.isBlank())                  // 过滤空串，防止 "ROLE_" 空权限
                    .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                    .toList();
        } else {
            authorities = loadAuthorities(userId);
        }

        // ============ 第 5 步：写入 SecurityContext，完成认证 ============
        // 构造已认证的 Authentication：
        //   principal（身份）= userId（Long），后续 Controller 可取当前用户 ID；
        //   credentials（凭证）= null，JWT 模式下无需保留密码；
        //   authorities = 上一步得到的角色权限列表。
        // 注：UsernamePasswordAuthenticationToken 虽名为 "Password"，但在无密码场景下
        //     也常被用作"已认证"状态的通用 Authentication 实现。
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        // 落锤动作：写入 SecurityContext。此后本次请求内的 @PreAuthorize、authorizeHttpRequests
        // 规则、Controller 都能感知到这是已登录且拥有这些角色的用户。
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // 把原始 token 暂存进 request 属性，供登出接口取出并删除 Redis 登录态（实现即时失效）。
        request.setAttribute(REQUEST_ATTR_TOKEN, token);

        // 放行到后续过滤器 / 业务代码。
        filterChain.doFilter(request, response);
    }

    /**
     * 降级查询用户角色权限（仅在 Redis 登录态未缓存角色时调用，正常流量不会走到）。
     * 流程：user_role 中间表取 roleId 列表 → role 表取 code → 映射为 Spring Security 的 {@code ROLE_xxx}。
     *
     * @param userId 用户 ID
     * @return 该用户拥有的权限列表（无角色时返回空列表，不抛异常）
     */
    private List<SimpleGrantedAuthority> loadAuthorities(Long userId) {
        // 1) 查 user_role 中间表，拿到该用户绑定的 roleId 列表（用户↔角色 多对多）。
        List<Long> roleIds = userRoleMapper.selectList(
                        Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        // 边界情况：用户未绑定任何角色，返回空列表（用户将以"已登录但无权限"继续，访问需角色接口会吃 403）。
        if (roleIds.isEmpty()) {
            return List.of();
        }
        // 2) 用 roleId 批量查 role 表，取出角色编码（如 USER / MERCHANT / ADMIN），
        //    3) 统一加 "ROLE_" 前缀映射为 Spring Security 权限（满足 RBAC 硬约定，@PreAuthorize/hasRole 才能匹配）。
        return roleMapper.selectByIds(roleIds).stream()
                .map(Role::getCode)
                .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                .toList();
    }

}