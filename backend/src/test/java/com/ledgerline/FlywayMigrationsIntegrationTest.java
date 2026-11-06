package com.ledgerline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FlywayMigrationsIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway migrations apply cleanly from empty and create all required schema tables")
    void testAllTablesCreated() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
            String.class
        );

        assertThat(tables).contains(
            "account_members",
            "accounts",
            "audit_log",
            "idempotency_keys",
            "ledger_attempts",
            "ledger_entries",
            "postings",
            "users"
        );
    }

    @Test
    @DisplayName("External counterparty account is seeded by migration")
    void testExternalWorldAccountSeeded() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM accounts WHERE name = 'External World' AND type = 'EXTERNAL'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("ledger_app role is created idempotently")
    void testLedgerAppRoleExists() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_roles WHERE rolname = 'ledger_app'",
            Integer.class
        );
        assertThat(count).isEqualTo(1);
    }
}

