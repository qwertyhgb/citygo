package com.citygo.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交注解（配合 {@code com.citygo.common.aspect.IdempotentSubmitAspect} 使用）。
 *
 * <p>标注在需要幂等的写接口（如下单）上：切面会以「用户 + X-Request-Id 请求头」为维度，
 * 在一个 TTL 窗口内只放行首次请求，后续相同请求直接返回 409 请勿重复提交。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentSubmit {

    /**
     * 幂等窗口时长（秒）：窗口内相同请求 ID 只允许一次。
     */
    long ttlSeconds() default 10;

}