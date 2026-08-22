package com.citygo.common.aspect;

import com.citygo.common.annotation.IdempotentSubmit;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 防重复提交切面。
 *
 * <p>思路（自定义注解 + AOP）：</p>
 * <ol>
 *   <li>从请求头取 {@code X-Request-Id}；</li>
 *   <li><b>无该请求头 → 直接放行</b>（学习项目约定；生产环境可强制要求客户端必带该头）；</li>
 *   <li>以 {@code citygo:idempotent:{userId}:{requestId}} 为 key 执行 {@code SETNX}：
 *       首次（SETNX=1）放行并设 TTL；非首次（SETNX=0）抛 409 请勿重复提交；</li>
 *   <li>key 中的 userId 从 SecurityContext 取，保证"同一请求号只对同一用户去重"。</li>
 * </ol>
 *
 * <p>切面内抛出的 {@link BizException} 会交给 {@link com.citygo.common.exception.GlobalExceptionHandler}
 * 统一转成标准 {@code Result} 返回，无需特殊处理。</p>
 */
@Aspect
@Component
public class IdempotentSubmitAspect {

    /** 幂等 key 前缀 */
    private static final String KEY_PREFIX = "citygo:idempotent:";

    /** 请求头名称：客户端生成的业务请求唯一标识 */
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotentSubmitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Around("@annotation(idempotentSubmit)")
    public Object around(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            // 非 Web 环境（如纯单元测试直调）直接放行
            return joinPoint.proceed();
        }
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        // 无请求头：学习项目约定直接放行，不强制
        if (requestId == null || requestId.isBlank()) {
            return joinPoint.proceed();
        }
        Long userId = currentUserId();
        // 幂等 key：用户维度 + 请求号
        String key = KEY_PREFIX + (userId == null ? "anonymous" : userId) + ":" + requestId;
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(idempotentSubmit.ttlSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException(ErrorCode.REPEAT_SUBMIT);
        }
        return joinPoint.proceed();
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

}