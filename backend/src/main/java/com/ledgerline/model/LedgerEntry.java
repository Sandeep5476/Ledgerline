package com.ledgerline.model;

import java.time.Instant;

public record LedgerEntry(
    Long id,
    EntryKind kind,
    Long reversesEntryId,
    Long initiatedBy,
    String idempotencyKey,
    String reason,
    Instant createdAt,
    String prevHash,
    String hash
) {}
