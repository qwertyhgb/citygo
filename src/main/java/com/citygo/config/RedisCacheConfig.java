package com.citygo.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存配置（Spring Cache 抽象）。
 *
 * <p>为什么用 Spring Cache 抽象，而不是自己写缓存读写：</p>
 * <ul>
 *   <li><b>声明式</b>：方法上加 {@code @Cacheable}/{@code @CacheEvict} 即可，缓存逻辑与业务代码解耦；</li>
 *   <li><b>AOP 生效</b>：注解由 {@link org.springframework.cache.interceptor.CacheInterceptor} 统一拦截，
 *       避免在业务里到处写"先查缓存再查库"的样板代码；</li>
 *   <li><b>解耦存储</b>：业务只依赖 Cache 接口，底层换成 Redis/本地缓存不影响业务。</li>
 * </ul>
 *
 * <p>序列化要求：Redis 中存 <b>JSON 字符串</b> 而非 JDK 序列化字节流。理由：
 * JSON 可读、可跨语言、便于用 redis-cli 直接排查；而 JDK 序列化是二进制字节，
 * 既不可读又强绑定 Java 类结构，排查与迁移都困难。</p>
 *
 * <p>key 命名：默认 {@link CacheKeyPrefix#simple()} 即「缓存名::参数」，如 {@code shopDetail::123}。</p>
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * 构建 RedisCacheManager：默认 TTL 10 分钟、key 前缀「缓存名::」、值用 JSON 序列化。
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        // JSON 值序列化器（Generic 版会在 JSON 里夹带 "@class" 类型信息，
        // 反序列化时据此还原成正确的 VO 类型；对 Spring Cache 这种"Object 进出"的场景是必须的）。
        // 这里用"不安全"的默认类型允许（使任意类都能带 @class），本项目 Redis 是可信内网，
        // 且仅缓存自有 VO，属学习场景下可接受的取舍；生产可改为 BasicPolymorphicTypeValidator 白名单。
        GenericJacksonJsonRedisSerializer valueSerializer =
                GenericJacksonJsonRedisSerializer.builder().enableUnsafeDefaultTyping().build();

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                // 默认 10 分钟
                .entryTtl(Duration.ofMinutes(10))
                // key 前缀：缓存名 + "::"（如 shopDetail::123）
                .computePrefixWith(CacheKeyPrefix.simple())
                // 详情接口查不到直接抛异常、不会缓存 null，故关闭空值缓存，
                // 避免引入 null 值序列化的额外兼容逻辑（空值缓存仅在"热门商品"手写缓存里做）。
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

}