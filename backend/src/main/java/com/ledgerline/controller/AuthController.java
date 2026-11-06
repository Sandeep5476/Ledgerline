package com.ledgerline.controller;

import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.LoginResponse;
import com.ledgerline.dto.UserDto;
import com.ledgerline.security.UserPrincipal;
import com.ledgerline.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getMe(@AuthenticationPrincipal UserPrincipal principal) {
        UserDto userDto = new UserDto(principal.id(), principal.username(), principal.role());
        return ResponseEntity.ok(userDto);
    }
}

