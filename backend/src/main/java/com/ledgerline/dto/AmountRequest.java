package com.ledgerline.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AmountRequest(
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly positive minor units")
    @Max(value = 100_000_000_000L, message = "Amount cannot exceed maximum transaction cap of 100,000,000,000 paise")
    Long amount,

    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String reason
) {}

