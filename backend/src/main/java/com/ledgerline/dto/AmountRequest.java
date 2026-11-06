package com.ledgerline.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AmountRequest(
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly positive")
    Long amount,

    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String reason
) {}

