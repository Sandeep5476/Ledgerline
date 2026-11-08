package com.ledgerline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdjustRequest(
    @NotNull(message = "Amount is mandatory")
    @Positive(message = "Amount must be strictly positive minor units")
    Long amount,
    String direction, // "CREDIT" (default) or "DEBIT"
    @NotBlank(message = "Adjustment reason is mandatory")
    @Size(min = 1, max = 500, message = "Adjustment reason must be between 1 and 500 characters")
    String reason
) {
}

