package com.ledgerline.model;

import java.time.Instant;

public record User(
    Long id,
    String username,
    String passwordHash,
    UserRole role,
    boolean enabled,
    Instant createdAt
) {}

