package com.ledgerline.service;

import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.LoginResponse;
import com.ledgerline.dto.UserDto;
import com.ledgerline.model.User;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    // Valid 12-round BCrypt hash for constant-time comparison when a user does not exist.
    // Prevents user enumeration via side-channel timing attacks.
    private static final String DUMMY_BCRYPT_HASH = "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final RateLimiterService rateLimiterService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        AuditService auditService,
        RateLimiterService rateLimiterService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.rateLimiterService = rateLimiterService;
    }

    public LoginResponse login(LoginRequest request) {
        return login(request, null);
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = request.username() != null ? request.username().trim() : "";
        rateLimiterService.checkLoginRateLimit(clientIp, username);

        Optional<User> userOptional = userRepository.findByUsername(username);

        if (userOptional.isEmpty()) {
            // Always run BCrypt calculation to normalize response timing
            passwordEncoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            auditService.logLoginFailure(username, "User not found");
            throw new BadCredentialsException("Invalid credentials");
        }

        User user = userOptional.get();
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.passwordHash());

        if (!passwordMatches || !user.enabled()) {
            auditService.logLoginFailure(username, "Invalid password or user disabled");
            throw new BadCredentialsException("Invalid credentials");
        }

        String token = jwtService.generateToken(user);
        UserDto userDto = new UserDto(user.id(), user.username(), user.role());

        auditService.logLoginSuccess(user.id(), user.username());

        return new LoginResponse(token, "Bearer", 1800, userDto);
    }
}

