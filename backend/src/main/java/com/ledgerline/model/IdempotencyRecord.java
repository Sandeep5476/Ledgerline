package com.ledgerline.model;

import java.time.Instant;

public record IdempotencyRecord(
    Long id,
    Long userId,
    String key,
    String requestHash,
    String status,
    Integer responseStatus,
    String responseBody,
    int duplicateCount,
    Instant createdAt
) {
}

