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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 接口限流切面（固定窗口计数器实现）。
 *
 * <p>实现：以 {@code citygo:rate:{用户或IP}:{类名}.{方法名}} 为 key，用 {@code INCR} 计数，
 * 第一次 INCR 返回 1 时顺带 {@code EXPIRE windowSeconds} 开启窗口；计数 &gt; limit 则抛 429。</p>
 *
 * <p><b>常见限流算法对比（面试点）</b>：</p>
 * <ul>
 *   <li>固定窗口（本实现）：实现最简单，但窗口边界处可能出现"双倍流量"毛刺；</li>
 *   <li>滑动窗口：把窗口再切分成更细的子窗口，边界更平滑，代价是内存稍高；</li>
 *   <li>令牌桶/漏桶：以恒定速率放行，能平滑突发流量，适合做"匀速"限流。</li>
 * </ul>
 */
@Aspect
@Component
public class RateLimitAspect {

    /** 限流 key 前缀 */
    private static final String KEY_PREFIX = "citygo:rate:";

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

        Long count = stringRedisTemplate.opsForValue().increment(key);
        // INCR 首次返回 1：说明是窗口内第一个请求，设置窗口过期时间
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(rateLimit.windowSeconds()));
        }
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
            return request.getRemoteAddr();
        }
        return "unknown";
    }

}