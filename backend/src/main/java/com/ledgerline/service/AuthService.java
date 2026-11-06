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

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        Optional<User> userOptional = userRepository.findByUsername(request.username().trim());

        if (userOptional.isEmpty()) {
            // Always run BCrypt calculation to normalize response timing
            passwordEncoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            throw new BadCredentialsException("Invalid credentials");
        }

        User user = userOptional.get();
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.passwordHash());

        if (!passwordMatches || !user.enabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String token = jwtService.generateToken(user);
        UserDto userDto = new UserDto(user.id(), user.username(), user.role());

        return new LoginResponse(token, "Bearer", 1800, userDto);
    }
}

