package com.ledgerline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseRequest(
    @NotBlank(message = "Reversal reason is mandatory")
    @Size(max = 255, message = "Reason cannot exceed 255 characters")
    String reason
) {}

