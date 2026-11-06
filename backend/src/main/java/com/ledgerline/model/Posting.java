package com.ledgerline.model;

import java.time.Instant;

public record Posting(
    Long id,
    Long entryId,
    Long accountId,
    long amount,
    long balanceAfter,
    Instant createdAt
) {}

