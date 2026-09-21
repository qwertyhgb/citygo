package com.citygo.common.aspect;

import com.citygo.common.annotation.RateLimit;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * 接口限流切面（滑动窗口实现，基于原子 Lua 脚本与可信代理感知的 IP 解析）。
 *
 * <p><b>为什么用滑动窗口而不是固定窗口计数器</b>：
 * 固定窗口在窗口交界处存在临界突刺——两个相邻窗口的边界 1 秒内可通过 2 倍流量
 * （两个窗口各打满配额）。滑动窗口以「当前时刻往前推 windowSeconds」为统计口径，
 * 任意时刻往前看一个完整窗口的请求数都不会超过 limit，彻底消除边界效应。</p>
 *
 * <p><b>实现</b>：以 {@code citygo:rate:{用户或IP}:{类名}.{方法名}} 为 ZSET key，
 * 每个请求作为一个 member（UUID 去重）以当前毫秒时间戳为 score 写入；
 * Lua 脚本原子执行「清理窗口外成员 → 统计窗口内请求数 → 未超限则写入并放行」。</p>
 */
@Aspect
@Component
public class RateLimitAspect {

    /** 限流 key 前缀 */
    private static final String KEY_PREFIX = "citygo:rate:";

    /**
     * 原子滑动窗口 Lua 脚本：
     * 1. {@code zremrangebyscore} 清理窗口外（score < now - windowMillis）的过期成员；
     * 2. {@code zcard} 统计窗口内请求数，已达 limit 则直接拒绝（不写入）；
     * 3. 未超限则 {@code zadd} 写入本次请求（UUID 成员防重），并 {@code pexpire} 刷新 key 过期时间。
     *
     * <p>返回值：1 放行；0 已达限流阈值。</p>
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "redis.call('zremrangebyscore', KEYS[1], 0, tonumber(ARGV[4]) - tonumber(ARGV[1]))\n" +
            "if redis.call('zcard', KEYS[1]) >= tonumber(ARGV[2]) then\n" +
            "    return 0\n" +
            "end\n" +
            "redis.call('zadd', KEYS[1], ARGV[4], ARGV[3])\n" +
            "redis.call('pexpire', KEYS[1], ARGV[1])\n" +
            "return 1",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /** 请求经过的可信反向代理层数（如 1 层 nginx 填 1）；0 表示不信任任何代理头 */
    private final int trustedProxyCount;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate,
                           @Value("${citygo.rate-limit.trusted-proxy-count:0}") int trustedProxyCount) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.trustedProxyCount = trustedProxyCount;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 以「类名.方法名」区分不同接口，避免同用户不同接口互相挤占配额
        String method = signature.getMethod().getDeclaringClass().getName()
                + "." + signature.getMethod().getName();
        Long userId = currentUserId();
        String subject = userId != null ? String.valueOf(userId) : clientIp();
        String key = KEY_PREFIX + subject + ":" + method;

        long now = System.currentTimeMillis();
        long windowMillis = rateLimit.windowSeconds() * 1000L;
        Long allowed = stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(windowMillis),
                String.valueOf(rateLimit.limit()),
                UUID.randomUUID().toString(),
                String.valueOf(now));

        // Redis 异常（返回 null）时放行，与原固定窗口实现保持一致：可用性优先，不因限流组件故障阻断业务
        if (allowed != null && allowed != 1L) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return joinPoint.proceed();
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    /**
     * 解析客户端真实 IP（用于未登录接口的限流主体）。
     *
     * <p><b>安全原则：代理头默认不可信。</b>{@code X-Forwarded-For} / {@code X-Real-IP}
     * 都是普通请求头，直连客户端可任意伪造；若无条件采信 XFF 的首段 IP，
     * 未登录接口（如登录、搜索）的 IP 限流只需每次换个伪造头即可绕过。
     * 因此默认直接返回 {@code remoteAddr}（TCP 层对端地址，无法伪造），
     * 只有显式配置了 {@code citygo.rate-limit.trusted-proxy-count}
     * （请求经过的可信反向代理层数）时才解析代理头。</p>
     *
     * <p><b>XFF 追加规则</b>：每层代理会把它看到的对端 IP 追加到 XFF 末尾，
     * 故从右往左数第 {@code trustedProxyCount} 个即真实客户端 IP。
     * 例：client → nginx → 应用，XFF = {@code 伪造段, clientIp}，取倒数第 1 个。
     * 若配置层数超过 XFF 实际跳数（头内容不可信），退回 remoteAddr——宁粗勿伪造。</p>
     */
    private String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        if (trustedProxyCount <= 0) {
            // 未声明可信代理：代理头不可信（可被直连客户端伪造），直接取 TCP 层对端地址
            return request.getRemoteAddr();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
            String[] hops = xff.split(",");
            int idx = hops.length - trustedProxyCount;
            // 从右往左数第 trustedProxyCount 个即真实客户端；idx 越界说明头不可信，退回 TCP 层地址
            return idx >= 0 ? hops[idx].trim() : request.getRemoteAddr();
        }
        // 无 XFF 但配置了可信代理：X-Real-IP 由可信代理覆盖式设置（客户端伪造值会被冲掉），可采信
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

}
