package com.ledgerline.seed;

import com.ledgerline.model.UserRole;
import com.ledgerline.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminPassword;
    private final String holderPassword;
    private final String auditorPassword;

    public DatabaseSeeder(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        @Value("${seed.admin.password:}") String adminPassword,
        @Value("${seed.holder.password:}") String holderPassword,
        @Value("${seed.auditor.password:}") String auditorPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.holderPassword = holderPassword;
        this.auditorPassword = auditorPassword;
    }

    @Override
    public void run(String... args) {
        validatePassword("SEED_ADMIN_PASSWORD", adminPassword);
        validatePassword("SEED_HOLDER_PASSWORD", holderPassword);
        validatePassword("SEED_AUDITOR_PASSWORD", auditorPassword);

        seedUserIfAbsent("admin", adminPassword, UserRole.ADMIN);
        seedUserIfAbsent("holder", holderPassword, UserRole.HOLDER);
        seedUserIfAbsent("auditor", auditorPassword, UserRole.AUDITOR);
    }

    private void validatePassword(String envVarName, String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                "FATAL: Missing required environment variable " + envVarName +
                ". Database initialization cannot proceed without secure initial seed credentials."
            );
        }
    }

    private void seedUserIfAbsent(String username, String rawPassword, UserRole role) {
        if (!userRepository.existsByUsername(username)) {
            log.info("Seeding initial {} user: {}", role, username);
            userRepository.create(username, passwordEncoder.encode(rawPassword), role);
        } else {
            log.info("User {} already exists; skipping seed.", username);
        }
    }
}

