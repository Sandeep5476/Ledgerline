package com.ledgerline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank(message = "Account name is mandatory")
    @Size(min = 1, max = 128, message = "Account name must be between 1 and 128 characters")
    String name
) {
}

