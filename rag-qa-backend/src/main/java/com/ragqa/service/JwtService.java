package com.ragqa.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 服务
 *
 * 【安全加固 2026-06-27】启动时强制校验 secret 长度，避免使用默认值导致的安全风险。
 */
@Service
@Slf4j
public class JwtService {

    /**
     * JWT 签名密钥
     *
     * 【为什么去掉默认值】原代码默认值 mySecretKeyForJWTTokenGenerationThatIsLongEnough123456
     * 是公开的开发密钥。如果生产部署忘记设置环境变量，会使用这个弱密钥，
     * 攻击者可以用它伪造任意用户的 token。
     *
     * 【正确配置方式】
     * application.yml 中不要设置 jwt.secret，或只写 jwt.secret=${JWT_SECRET} 强制要求环境变量。
     * 生产环境必须设置 JWT_SECRET 为强随机密钥（≥ 256 bit for HS256）。
     */
    @Value("${jwt.secret:}")
    private String secretKey;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration;

    /**
     * 启动时校验 JWT 配置
     */
    @PostConstruct
    public void validateJwtConfig() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置！请设置环境变量 JWT_SECRET 或 application.yml 中的 jwt.secret。" +
                    "密钥长度至少 32 字节（HS256 推荐 256 bit）。");
        }
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secretKey);
            if (keyBytes.length < 32) {
                throw new IllegalStateException(
                        "JWT 密钥长度不足！当前 " + keyBytes.length + " 字节，至少需要 32 字节（256 bit）。");
            }
            log.info("JWT 配置校验通过，密钥长度 {} 字节", keyBytes.length);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT 密钥不是合法的 Base64 字符串: " + e.getMessage(), e);
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, jwtExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSignInKey(), Jwts.SIG.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignInKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSignInKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
