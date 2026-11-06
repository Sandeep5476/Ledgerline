package com.ledgerline.model;

import java.time.Instant;

public record AuditLogEntry(
    Long id,
    Long actorUserId,
    String action,
    String targetType,
    String targetId,
    String details,
    String outcome,
    Instant createdAt,
    String prevHash,
    String hash
) {
}

