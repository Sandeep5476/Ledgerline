package com.ledgerline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank(message = "Username cannot be blank")
    @Size(max = 64, message = "Username too long")
    String username,

    @NotBlank(message = "Password cannot be blank")
    @Size(max = 128, message = "Password too long")
    String password
) {}

