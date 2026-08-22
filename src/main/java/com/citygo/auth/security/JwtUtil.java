package com.citygo.auth.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（jjwt 0.13）。
 *
 * <p>负责令牌的生成与解析校验。使用 jjwt 0.13 的新 API（builder/parser 风格），
 * 严禁使用旧版已废弃的 setSubject / parseClaimsJws 等方法。</p>
 *
 * <p>secret 为 HS256 对称密钥，来自配置 citygo.jwt.secret（开发默认值仅供本地，
 * 生产必须走环境变量）。</p>
 */
@Component
public class JwtUtil {

    /** HS256 对称签名密钥 */
    private final SecretKey key;

    /** 令牌有效期（秒） */
    private final long expireSeconds;

    /**
     * 构造器从配置注入密钥与有效期。密钥至少需 32 字节以满足 HS256 要求。
     */
    public JwtUtil(@Value("${citygo.jwt.secret}") String secret,
                   @Value("${citygo.jwt.expire-seconds}") long expireSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    /**
     * 生成 JWT：subject 为用户ID，声名 username，签发/过期时间与有效期联动。
     *
     * @param userId   用户ID
     * @param username 用户名
     * @return 签名后的 token 字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 并校验签名与有效期，返回载荷中的用户ID。
     *
     * @param token JWT 字符串
     * @return 用户ID
     * @throws io.jsonwebtoken.JwtException token 非法或已过期时抛出
     */
    public Long parseUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.parseLong(subject);
    }

}