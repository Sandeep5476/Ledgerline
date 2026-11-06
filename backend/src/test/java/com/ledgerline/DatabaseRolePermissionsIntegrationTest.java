package com.ledgerline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DatabaseRolePermissionsIntegrationTest extends BasePostgresIntegrationTest {

    private JdbcTemplate appJdbcTemplate;

    @BeforeEach
    void setUpAppRoleDataSource() {
        // Connect specifically with the restricted runtime role 'ledger_app'
        PGSimpleDataSource ds = new PGSimpleDataSource();

        Pattern pattern = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)");
        Matcher matcher = pattern.matcher(JDBC_URL);
        if (matcher.find()) {
            ds.setServerNames(new String[]{matcher.group(1)});
            ds.setPortNumbers(new int[]{Integer.parseInt(matcher.group(2))});
            ds.setDatabaseName(matcher.group(3));
        } else {
            throw new IllegalStateException("Failed to parse PostgreSQL JDBC URL: " + JDBC_URL);
        }

        ds.setUser("ledger_app");
        ds.setPassword(APP_PASSWORD);

        this.appJdbcTemplate = new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("ledger_app can query ledger_entries")
    void testAppCanSelectLedgerEntries() {
        Integer count = appJdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
        assertThat(count).isNotNull();
    }

    @Test
    @DisplayName("ledger_app CANNOT UPDATE ledger_entries (Permission Denied)")
    void testAppCannotUpdateLedgerEntries() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("UPDATE ledger_entries SET reason = 'tampered'"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT DELETE from ledger_entries (Permission Denied)")
    void testAppCannotDeleteLedgerEntries() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("DELETE FROM ledger_entries"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT TRUNCATE ledger_entries (Permission Denied)")
    void testAppCannotTruncateLedgerEntries() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("TRUNCATE ledger_entries"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT UPDATE postings (Permission Denied)")
    void testAppCannotUpdatePostings() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("UPDATE postings SET amount = 1000"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT DELETE from postings (Permission Denied)")
    void testAppCannotDeletePostings() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("DELETE FROM postings"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT TRUNCATE postings (Permission Denied)")
    void testAppCannotTruncatePostings() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("TRUNCATE postings"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT UPDATE audit_log (Permission Denied)")
    void testAppCannotUpdateAuditLog() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("UPDATE audit_log SET outcome = 'TAMPERED'"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT DELETE from audit_log (Permission Denied)")
    void testAppCannotDeleteAuditLog() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("DELETE FROM audit_log"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT TRUNCATE audit_log (Permission Denied)")
    void testAppCannotTruncateAuditLog() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("TRUNCATE audit_log"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT UPDATE ledger_attempts (Permission Denied)")
    void testAppCannotUpdateLedgerAttempts() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("UPDATE ledger_attempts SET refusal_reason = 'tampered'"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT DELETE from ledger_attempts (Permission Denied)")
    void testAppCannotDeleteLedgerAttempts() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("DELETE FROM ledger_attempts"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    @DisplayName("ledger_app CANNOT TRUNCATE ledger_attempts (Permission Denied)")
    void testAppCannotTruncateLedgerAttempts() {
        assertThatThrownBy(() -> appJdbcTemplate.execute("TRUNCATE ledger_attempts"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("permission denied"));
    }
}
