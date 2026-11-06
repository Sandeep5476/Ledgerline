package com.ledgerline.model;

import java.time.Instant;

public record LedgerAttempt(
    Long id,
    String kind,
    Long fromAccountId,
    Long toAccountId,
    long amount,
    Long initiatedBy,
    String idempotencyKey,
    String status,
    String refusalReason,
    Long balanceSnapshot,
    Instant createdAt
) {}

