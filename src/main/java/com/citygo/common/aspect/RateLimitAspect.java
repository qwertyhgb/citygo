package com.citygo.common.aspect;

import com.citygo.common.annotation.RateLimit;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 接口限流切面（固定窗口计数器实现，基于原子 Lua 脚本与反向代理 IP 解析）。
 *
 * <p>实现：以 {@code citygo:rate:{用户或IP}:{类名}.{方法名}} 为 key，
 * 通过 Lua 脚本原子执行 {@code INCR} + 首次 {@code EXPIRE windowSeconds}，杜绝非原子操作导致的死 key；
 * 计数 &gt; limit 则抛 429。</p>
 */
@Aspect
@Component
public class RateLimitAspect {

    /** 限流 key 前缀 */
    private static final String KEY_PREFIX = "citygo:rate:";

    /**
     * 原子限流 Lua 脚本：自增并对首次自增设置过期时间。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('incr', KEYS[1])\n" +
            "if count == 1 then\n" +
            "    redis.call('expire', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return count",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
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

        Long count = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(key),
                String.valueOf(rateLimit.windowSeconds()));

        if (count != null && count > rateLimit.limit()) {
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

    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
                int idx = xff.indexOf(',');
                return idx != -1 ? xff.substring(0, idx).trim() : xff.trim();
            }
            String xRealIp = request.getHeader("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank() && !"unknown".equalsIgnoreCase(xRealIp)) {
                return xRealIp.trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }

}