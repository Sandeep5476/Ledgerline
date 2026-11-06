package com.ledgerline.dto;

import com.ledgerline.model.LedgerAttempt;
import com.ledgerline.model.LedgerEntry;
import com.ledgerline.model.Posting;

import java.util.List;

public record LedgerResult(
    boolean success,
    LedgerEntry entry,
    List<Posting> postings,
    LedgerAttempt attempt,
    RefusalCode refusalCode,
    String refusalReason
) {
    public static LedgerResult success(LedgerEntry entry, List<Posting> postings) {
        return new LedgerResult(true, entry, postings, null, null, null);
    }

    public static LedgerResult refused(LedgerAttempt attempt, RefusalCode code, String reason) {
        return new LedgerResult(false, null, null, attempt, code, reason);
    }
}
