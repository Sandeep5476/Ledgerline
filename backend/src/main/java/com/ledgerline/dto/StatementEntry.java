package com.ledgerline.dto;

import java.time.Instant;

public record StatementEntry(
    String type,
    String kind,
    long amount,
    Long runningBalance,
    String initiatorUsername,
    String idempotencyKey,
    Long entryId,
    String reason,
    Instant createdAt
) {
}

