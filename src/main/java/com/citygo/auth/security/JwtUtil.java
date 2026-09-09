package com.citygo.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（基于 jjwt 0.13 新 API）。
 *
 * <h3>职责边界</h3>
 * 本类是一个<b>纯工具类</b>，只负责两件最小的事：
 * <ol>
 *     <li>{@link #generateToken(Long, String)} —— 根据用户信息签发（生成）token；</li>
 *     <li>{@link #parseUserId(String)} —— 校验 token 的签名与有效期，并提取其中的用户 ID。</li>
 * </ol>
 * 它本身<b>不接触 Redis、不查询数据库、不管理登录态</b>，这些职责分别由
 * {@code JwtAuthenticationFilter}（校验 Redis 登录态 + 加载角色）与
 * {@code AuthServiceImpl}（登录时签发 token 并写入 Redis）承担。
 * 这样拆分使得本类职责单一、可独立单元测试、可被多处安全复用。
 *
 * <h3>使用的 JWT 算法</h3>
 * 采用 <b>HS256（HMAC + SHA-256）</b> 对称签名算法：
 * <ul>
 *     <li>签名与验签使用<b>同一把密钥</b> {@code key}（见构造器）；</li>
 *     <li>HS256 要求密钥长度<b>至少 32 字节（256 位）</b>，否则 {@code Keys.hmacShaKeyFor} 会抛异常；</li>
 *     <li>对称算法适用于「签发与验签都在同一服务内」的单体架构，本项目正是此场景，故不引入非对称 RS256。</li>
 * </ul>
 *
 * <h3>API 版本注意</h3>
 * 本项目使用 jjwt <b>0.13</b>，必须使用新的 builder / parser 风格 API：
 * {@code Jwts.builder()...compact()} 与 {@code Jwts.parser().verifyWith(key)...}。
 * <b>严禁使用旧版已废弃的 {@code setSubject} / {@code parseClaimsJws} 等方法</b>（0.12+ 已移除）。
 *
 * <h3>密钥来源</h3>
 * 密钥来自配置项 {@code citygo.jwt.secret}。开发默认值仅供本地调试，
 * <b>生产环境必须通过环境变量注入</b>（如 {@code CITYGO_JWT_SECRET}），禁止硬编码到仓库中。
 */
@Component
public class JwtUtil {

    /** HS256 对称签名密钥。构造后不可变（final），供生成与解析两处共用，保证验签密钥一致。 */
    private final SecretKey key;

    /** 令牌有效期，单位为「秒」，来自配置 {@code citygo.jwt.expire-seconds}。 */
    private final long expireSeconds;

    /**
     * 构造器：从 Spring 配置注入密钥字符串与有效期，并据此构建 {@link SecretKey}。
     *
     * <p>两个字段均为 {@code final}，在构造后不再变化，因此本类<b>线程安全</b>，
     * 整个应用共享同一个单例即可，无需担心并发问题。</p>
     *
     * <p>关于密钥长度：{@link Keys#hmacShaKeyFor(byte[])} 要求输入字节数组
     * （按 UTF-8 编码）长度 ≥ 32 字节，否则会在构造时直接抛出
     * {@code WeakKeyException}。配置密钥时务必满足此要求。</p>
     *
     * @param secret         JWT 签名密钥（原始字符串，将被按 UTF-8 转字节后用于 HS256）
     * @param expireSeconds  token 有效期（秒），如 86400 表示 1 天
     */
    public JwtUtil(@Value("${citygo.jwt.secret}") String secret,
                   @Value("${citygo.jwt.expire-seconds}") long expireSeconds) {
        // 将配置字符串按 UTF-8 编码为字节，并适配成符合 HS256 强度要求的 SecretKey。
        // 这一步同时完成了「密钥合法性校验」：长度不足会立即失败，避免运行时才暴露问题。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    /**
     * 生成（签发）JWT。
     *
     * <p>token 的载荷（payload）中包含以下声明（claims）：</p>
     * <ul>
     *     <li>{@code sub}（subject）= 用户 ID（字符串形式）。选用<b>稳定不变的主键</b>作为主体，
     *         而非用户名——因为用户名可被用户修改，而用户 ID（雪花 ID）不会变，更适合做鉴权标识；</li>
     *     <li>{@code username} = 自定义声明，便于日志/排查时识别用户身份，<b>仅作为辅助信息，不参与鉴权</b>；</li>
     *     <li>{@code iat}（issuedAt）= 签发时间；</li>
     *     <li>{@code exp}（expiration）= 过期时间；jjwt 在解析时会自动校验该字段，过期即抛 {@code ExpiredJwtException}。</li>
     * </ul>
     *
     * <p>签名使用构造器中的同一个 {@code key}（HS256）。{@code .compact()} 将
     * header.payload.signature 三段拼接并 Base64URL 编码，得到最终可传输的字符串。</p>
     *
     * @param userId   用户 ID（作为 JWT 的 subject）
     * @param username 用户名（作为自定义声明，辅助信息）
     * @return 签名后的 JWT 字符串（格式：{@code xxxxx.yyyyy.zzzzz}）
     */
    public String generateToken(Long userId, String username) {
        // 以当前时间为基准，计算过期时间点：now + expireSeconds 秒。
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                // 标准声明 sub：主体 = 用户 ID。Long 转为 String 以符合 JWT 字符串规范。
                .subject(String.valueOf(userId))
                // 自定义声明：用户名，仅用于展示/排查，不影响鉴权逻辑。
                .claim("username", username)
                // 标准声明 iat：签发时间。
                .issuedAt(now)
                // 标准声明 exp：过期时间。解析时 jjwt 自动校验。
                .expiration(expiry)
                // 使用 HS256 对称密钥签名（不显式指定算法时，jjwt 根据 key 类型推断为 HS256）。
                .signWith(key)
                // 序列化为最终的三段式 token 字符串。
                .compact();
    }

    /**
     * 解析并校验 token，返回其中的用户 ID。
     *
     * <p>该方法内部由 jjwt 完成以下<b>全部校验</b>，任一不通过都会抛出对应的 {@link io.jsonwebtoken.JwtException} 子类：</p>
     * <ol>
     *     <li><b>签名校验</b>：用 {@code key} 验证 token 确实由本服务签发、且未被篡改；</li>
     *     <li><b>过期校验</b>：检查 {@code exp} 是否已超过当前时间；</li>
     *     <li><b>格式校验</b>：检查 token 结构是否合法（三段式、Base64URL 可解码等）。</li>
     * </ol>
     *
     * <p>设计要点：</p>
     * <ul>
     *     <li>本方法<b>只提取并返回用户 ID</b>，不做角色/权限查询——权限由 {@code JwtAuthenticationFilter} 负责；</li>
     *     <li>本方法<b>不捕获异常</b>，校验失败直接向上抛出。真正的异常处理在调用方
     *         （{@code JwtAuthenticationFilter.doFilterInternal}），那里会捕获后按「未认证」放行，
     *         最终由 {@code SecurityConfig} 的入口点统一返回 401 JSON。职责由此清晰分离。</li>
     * </ul>
     *
     * @param token 待校验的 JWT 字符串
     * @return token 中携带的用户 ID（由 subject 还原回 Long）
     * @throws io.jsonwebtoken.JwtException 当 token 被篡改、已过期或格式非法时抛出
     *         （常见子类：{@code SignatureException}、{@code ExpiredJwtException}、{@code MalformedJwtException}）
     */
    public Long parseUserId(String token) {
        // 1) 构建解析器并指定验签密钥；2) parseSignedClaims 执行「验签 + 解析 + 过期校验」；
        // 3) getPayload() 取得 claims；4) getSubject() 取出 sub（即用户 ID 字符串）。
        String subject = Jwts.parser()
                .verifyWith(key)                       // 指定 HS256 验签密钥，与生成时一致
                .build()
                .parseSignedClaims(token)              // 验签 + 解析，失败即抛 JwtException
                .getPayload()                         // 获取载荷（claims）
                .getSubject();                        // 取标准声明 sub
        // subject 是字符串形式的用户 ID，还原为 Long 返回给调用方。
        return Long.parseLong(subject);
    }

}