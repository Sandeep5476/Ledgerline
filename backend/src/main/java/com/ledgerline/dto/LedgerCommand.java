package com.ledgerline.dto;

import com.ledgerline.model.EntryKind;
import com.ledgerline.model.UserRole;

public record LedgerCommand(
    EntryKind kind,
    Long fromAccountId,
    Long toAccountId,
    long amount,
    Long callerId,
    UserRole callerRole,
    String reason,
    String idempotencyKey
) {}
