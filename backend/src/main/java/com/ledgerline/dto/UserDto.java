package com.ledgerline.dto;

import com.ledgerline.model.UserRole;

public record UserDto(
    Long id,
    String username,
    UserRole role
) {}

