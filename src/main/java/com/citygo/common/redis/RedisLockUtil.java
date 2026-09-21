package com.citygo.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁工具。
 *
 * <p><b>分布式锁解决什么问题</b>：单机环境下 {@code synchronized}/{@code ReentrantLock} 够用，
 * 但服务部署多实例时，JVM 内的锁互不了其他实例。Redis 是多个实例共享的外部存储，
 * 用「SET key val NX EX」这条原子命令即可让所有实例在同一把锁上互斥。</p>
 *
 * <p><b>与 Phase 6 数据库条件更新的对比</b>：</p>
 * <ul>
 *   <li>数据库行锁/条件更新（{@code UPDATE ... WHERE stock >= ?}）解决的是<b>数据一致性</b>问题，
 *       锁的粒度是"某一行"，天然伴随事务、失败即回滚，适合防超卖；</li>
 *   <li>Redis 分布式锁解决的是<b>跨实例的业务互斥/防重复</b>问题（如唯一性校验、"缓存重建"只让一个线程做），
 *       粒度是"某个业务 key"，更轻量、不依赖数据库事务。</li>
 * </ul>
 *
 * <p>本工具用「SET key value EX expire NX」加锁，value 用 UUID 作为<b>持有者标识</b>——
 * 解锁时先比对 value 再删除，防止"锁已超时被别人拿走、自己又去删锁"的误删场景。</p>
 */
@Component
public class RedisLockUtil {

    /**
     * 原子解锁 Lua 脚本：只有 value 匹配才删除，避免误删他人锁。
     *
     * <p><b>为什么必须用 Lua</b>：解锁 = 「GET 比对 + DEL」两步。若分开执行，
     * 在比对的瞬间锁可能刚好超时被他人获取，此时再 DEL 就会误删他人的锁。
     * Lua 脚本在 Redis 服务端一次性原子执行，杜绝了比对与删除之间的间隙。</p>
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisLockUtil(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 加锁并返回自动生成的持有者标识（Token）：{@code SET key uuid EX expireSeconds NX}。
     * 成功返回持锁 token，调用方在 finally 中传给 {@link #unlock(String, String)} 安全解锁。
     *
     * @param key           锁的 key
     * @param expireSeconds 锁过期秒数（防止持锁方宕机导致死锁）
     * @return 获取成功返回持有者 token，已被他人持有返回 null
     */
    public String tryLockWithToken(String key, long expireSeconds) {
        String token = UUID.randomUUID().toString();
        boolean ok = tryLock(key, expireSeconds, token);
        return ok ? token : null;
    }

    /**
     * 加锁（自动生成持有者标识，仅用于无需主动解锁、完全依赖 TTL 自然过期的互斥场景）。
     * 若需要显式在 finally 中安全解锁，请使用 {@link #tryLockWithToken(String, long)} 或 {@link #tryLock(String, long, String)}。
     *
     * @param key           锁的 key
     * @param expireSeconds 锁过期秒数（防止持锁方宕机导致死锁）
     * @return 获取成功返回 true，已被他人持有返回 false
     */
    public boolean tryLock(String key, long expireSeconds) {
        return tryLock(key, expireSeconds, UUID.randomUUID().toString());
    }

    /**
     * 加锁（手动指定持有者标识，便于稍后安全解锁）。
     *
     * @param key           锁的 key
     * @param expireSeconds 锁过期秒数
     * @param token         持有者标识（UUID/线程标识）
     * @return 获取成功返回 true
     */
    public boolean tryLock(String key, long expireSeconds, String token) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, Duration.ofSeconds(expireSeconds));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 解锁（Lua 原子执行，只有持有者标识匹配才删除）。
     *
     * @param key   锁的 key
     * @param token 加锁时使用的持有者标识
     */
    public void unlock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
    }

}