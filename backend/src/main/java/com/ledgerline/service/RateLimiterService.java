package com.ledgerline.service;

import com.ledgerline.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service providing rate limiting using Bucket4j token bucket algorithms.
 *
 * ARCHITECTURAL NOTES & PROXY HANDLING:
 * 1. IN-MEMORY LIMITS ARE PER-INSTANCE:
 *    The token buckets are stored in memory using ConcurrentHashMap. In a horizontally scaled
 *    cluster, these limits apply per application instance. For distributed production clusters,
 *    Bucket4j supports distributed backends (e.g. Redis or Hazelcast) using the same token bucket logic.
 *
 * 2. PROXY & X-FORWARDED-FOR SPOOFING DEFENSE:
 *    When 'rate-limiting.trusted-proxy-enabled' is false (default for local / untrusted setups),
 *    the service strictly uses request.getRemoteAddr() and completely ignores any client-supplied
 *    'X-Forwarded-For' header. This prevents attackers from bypassing IP rate limiting by rotating
 *    fake X-Forwarded-For IPs.
 *    When deployed behind a trusted reverse proxy (such as Render or AWS ALB with trusted-proxy-enabled=true),
 *    Render terminates TLS and appends the verified client IP into X-Forwarded-For. In that mode,
 *    the leftmost client IP is extracted securely.
 */
@Service
public class RateLimiterService {

    private final boolean trustedProxyEnabled;
    private final int loginIpCapacity;
    private final int loginUserCapacity;
    private final int moneyUserCapacity;

    private final Map<String, Bucket> loginIpBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> loginUserBuckets = new ConcurrentHashMap<>();
    private final Map<Long, Bucket> moneyUserBuckets = new ConcurrentHashMap<>();

    public RateLimiterService(
        @Value("${rate-limiting.trusted-proxy-enabled:false}") boolean trustedProxyEnabled,
        @Value("${rate-limiting.login-ip-capacity:10}") int loginIpCapacity,
        @Value("${rate-limiting.login-user-capacity:5}") int loginUserCapacity,
        @Value("${rate-limiting.money-user-capacity:20}") int moneyUserCapacity
    ) {
        this.trustedProxyEnabled = trustedProxyEnabled;
        this.loginIpCapacity = loginIpCapacity;
        this.loginUserCapacity = loginUserCapacity;
        this.moneyUserCapacity = moneyUserCapacity;
    }

    public void checkLoginRateLimit(String clientIp, String username) {
        // 1. Check IP bucket
        if (clientIp != null && !clientIp.isBlank()) {
            Bucket ipBucket = loginIpBuckets.computeIfAbsent(clientIp, k -> createBucket(loginIpCapacity, Duration.ofMinutes(1)));
            consumeOrThrow(ipBucket, "Too many login attempts from this IP address");
        }

        // 2. Check Username bucket
        if (username != null && !username.isBlank()) {
            Bucket userBucket = loginUserBuckets.computeIfAbsent(username.toLowerCase(), k -> createBucket(loginUserCapacity, Duration.ofMinutes(1)));
            consumeOrThrow(userBucket, "Too many login attempts for this user account");
        }
    }

    public void checkMoneyRateLimit(Long userId) {
        if (userId != null) {
            Bucket userBucket = moneyUserBuckets.computeIfAbsent(userId, k -> createBucket(moneyUserCapacity, Duration.ofMinutes(1)));
            consumeOrThrow(userBucket, "Transaction rate limit exceeded for this account");
        }
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (!trustedProxyEnabled) {
            // Strictly ignore client-supplied X-Forwarded-For headers to prevent spoofing
            return request.getRemoteAddr();
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private Bucket createBucket(int capacity, Duration refillDuration) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, refillDuration));
        return Bucket.builder().addLimit(limit).build();
    }

    private void consumeOrThrow(Bucket bucket, String errorMessage) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long waitForRefillNanos = probe.getNanosToWaitForRefill();
            long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitForRefillNanos));
            throw new RateLimitExceededException(retryAfterSeconds, errorMessage + ". Try again in " + retryAfterSeconds + " seconds.");
        }
    }
}

