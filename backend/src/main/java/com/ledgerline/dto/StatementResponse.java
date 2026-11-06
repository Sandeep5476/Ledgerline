package com.ledgerline.dto;

import java.util.List;

public record StatementResponse(
    Long accountId,
    int page,
    int size,
    long totalElements,
    List<StatementEntry> entries
) {
}

