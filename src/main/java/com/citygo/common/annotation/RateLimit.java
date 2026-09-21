package com.citygo.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（配合 {@code com.citygo.common.aspect.RateLimitAspect} 使用）。
 *
 * <p>标注在需要限流的接口上：切面用 Redis ZSET 滑动窗口统计「当前时刻往前
 * windowSeconds」内的请求次数，超过阈值返回 429 请求过于频繁。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 窗口内的最大允许请求数。
     */
    int limit() default 10;

    /**
     * 窗口时长（秒）。
     */
    int windowSeconds() default 1;

}