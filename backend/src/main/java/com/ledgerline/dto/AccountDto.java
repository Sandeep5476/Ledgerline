package com.ledgerline.dto;

import java.time.Instant;

public record AccountDto(
    Long id,
    String name,
    String type,
    String currency,
    long balance,
    Instant createdAt
) {
}

