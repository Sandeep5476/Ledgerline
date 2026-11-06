package com.ledgerline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BasePostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BasePostgresIntegrationTest.class);

    public static final PostgreSQLContainer<?> postgres;
    public static final String JDBC_URL;
    public static final String DB_USER;
    public static final String DB_PASS;
    public static final String APP_PASSWORD = "AppSecret_2026!";

    static {
        System.setProperty("user.timezone", "UTC");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));

        PostgreSQLContainer<?> container = null;
        String url;
        String user;
        String pass;

        try {
            log.info("Attempting to initialize Testcontainers PostgreSQL 16 container...");
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("ledgerline")
                .withUsername("ledger_owner")
                .withPassword("OwnerSecret_2026!");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            pass = container.getPassword();
            log.info("Testcontainers PostgreSQL started at {}", url);
        } catch (Throwable t) {
            log.warn("Testcontainers initialization encountered environment restriction ({}). Falling back to local PostgreSQL 16 instance.", t.getMessage());
            // Fallback to local PostgreSQL 16 service (Real PostgreSQL 16, strictly NO H2)
            url = "jdbc:postgresql://localhost:5433/ledgerline?options=-c%20timezone=UTC";
            user = "ledger_owner";
            pass = "LedgerLocal_2026!";
        }

        postgres = container;
        JDBC_URL = url;
        DB_USER = user;
        DB_PASS = pass;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", () -> JDBC_URL);
        registry.add("spring.flyway.user", () -> DB_USER);
        registry.add("spring.flyway.password", () -> DB_PASS);
        registry.add("spring.flyway.placeholders.app_db_password", () -> APP_PASSWORD);
        registry.add("spring.flyway.clean-disabled", () -> false);

        // Application datasource
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASS);

        registry.add("jwt.secret", () -> "a_very_secret_and_long_jwt_signing_key_32_bytes_min!");
        registry.add("seed.admin.password", () -> "AdminPassword_2026!");
        registry.add("seed.holder.password", () -> "HolderPassword_2026!");
        registry.add("seed.auditor.password", () -> "AuditorPassword_2026!");
    }
}
