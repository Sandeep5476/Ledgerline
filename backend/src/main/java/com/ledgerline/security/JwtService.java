package com.ledgerline.security;

import com.ledgerline.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final String secret;
    private final long expirationMs;
    private SecretKey key;

    public JwtService(
        @Value("${jwt.secret:}") String secret,
        @Value("${jwt.expiration-ms:1800000}") long expirationMs
    ) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    @PostConstruct
    public void validateAndInitialize() {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "FATAL: JWT_SECRET environment variable is missing or shorter than 32 bytes (256 bits). " +
                "Application startup aborted due to insufficient cryptographic key length."
            );
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(expirationMs);

        return Jwts.builder()
            .subject(String.valueOf(user.id()))
            .claim("username", user.username())
            .claim("role", user.role().name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public Claims parseAndValidateToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidJwtException("Invalid or expired JWT token");
        }
    }
}

