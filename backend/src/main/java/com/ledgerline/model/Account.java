package com.ledgerline.model;

import java.time.Instant;

public record Account(
    Long id,
    String name,
    AccountType type,
    String currency,
    long balance,
    Instant createdAt
) {}

