package com.ledgerline.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TransferRequest(
    @NotNull(message = "Source account ID is required")
    @Positive(message = "Source account ID must be positive")
    Long fromAccountId,

    @NotNull(message = "Destination account ID is required")
    @Positive(message = "Destination account ID must be positive")
    Long toAccountId,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be strictly positive minor units")
    @Max(value = 100_000_000_000L, message = "Amount cannot exceed maximum transaction cap of 100,000,000,000 paise")
    Long amount,

    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String reason
) {}

