package com.ledgerline.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddMemberRequest(
    @NotNull(message = "User ID is mandatory")
    @Positive(message = "User ID must be a positive integer")
    Long userId
) {
}

