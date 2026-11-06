package com.ledgerline.dto;

public record LoginResponse(
    String token,
    String tokenType,
    long expiresInSeconds,
    UserDto user
) {}

