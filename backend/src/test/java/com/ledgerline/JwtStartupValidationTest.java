package com.ledgerline;

import com.ledgerline.security.JwtService;
import com.ledgerline.seed.DatabaseSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JwtStartupValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "short_secret_under_32_bytes!",
        "exactly_31_bytes_long_secret_12"
    })
    @DisplayName("JwtService fails validation when secret is missing or shorter than 32 bytes")
    void testJwtSecretTooShortThrows(String weakSecret) {
        JwtService service = new JwtService(weakSecret, 1800000);
        assertThatThrownBy(service::validateAndInitialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET environment variable is missing or shorter than 32 bytes");
    }

    @Test
    @DisplayName("JwtService fails validation when secret is null")
    void testJwtSecretNullThrows() {
        JwtService service = new JwtService(null, 1800000);
        assertThatThrownBy(service::validateAndInitialize)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT_SECRET environment variable is missing or shorter than 32 bytes");
    }

    @Test
    @DisplayName("JwtService successfully initializes when secret is at least 32 bytes")
    void testJwtSecretValidLength() {
        JwtService service = new JwtService("a_valid_cryptographic_secret_key_that_is_at_least_32_bytes_long!", 1800000);
        assertThatCode(service::validateAndInitialize).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DatabaseSeeder fails startup when required seed passwords are missing")
    void testDatabaseSeederFailsWhenPasswordMissing() {
        DatabaseSeeder seeder = new DatabaseSeeder(null, null, "", "holder123", "auditor123");
        assertThatThrownBy(seeder::run)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SEED_ADMIN_PASSWORD");
    }
}

