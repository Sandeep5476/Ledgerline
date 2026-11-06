package com.ledgerline;

import com.ledgerline.dto.AmountRequest;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import com.ledgerline.model.User;
import com.ledgerline.model.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import com.ledgerline.service.AuditService;
import com.ledgerline.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IdempotencyAndAuditIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User adminUser;
    private User holderUser;
    private User auditorUser;
    private String holderToken;
    private String adminToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());

        adminUser = userRepository.findByUsername("admin").orElseThrow();
        holderUser = userRepository.findByUsername("holder").orElseThrow();
        auditorUser = userRepository.findByUsername("auditor").orElseThrow();

        adminToken = jwtService.generateToken(adminUser);
        holderToken = jwtService.generateToken(holderUser);
        auditorToken = jwtService.generateToken(auditorUser);
    }

    private HttpHeaders createHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    @Test
    @DisplayName("Missing, blank, over-long, or whitespace-padded Idempotency-Key returns 400 Bad Request")
    void testInvalidIdempotencyKeysRejectedWith400() {
        Account acc = accountRepository.createAccount("Key Validation Test", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc.id(), holderUser.id());

        AmountRequest req = new AmountRequest(1000L, "test");

        // 1. Missing header
        HttpHeaders missingHeader = createHeaders(holderToken, null);
        ResponseEntity<Map> res1 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(req, missingHeader),
            Map.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2. Blank header
        HttpHeaders blankHeader = createHeaders(holderToken, "   ");
        ResponseEntity<Map> res2 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(req, blankHeader),
            Map.class
        );
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 3. Whitespace in header
        HttpHeaders whitespaceHeader = createHeaders(holderToken, "key with space");
        ResponseEntity<Map> res3 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(req, whitespaceHeader),
            Map.class
        );
        assertThat(res3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 4. Over-long header (> 128 characters)
        String overLongKey = "a".repeat(129);
        HttpHeaders longHeader = createHeaders(holderToken, overLongKey);
        ResponseEntity<Map> res4 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(req, longHeader),
            Map.class
        );
        assertThat(res4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Replaying a transfer returns identical cached response, does not move money twice, increments duplicate_count")
    void testTransferIdempotencyReplay() {
        Account source = accountRepository.createAccount("Idemp Src", AccountType.USER, "INR", 10_000L);
        Account dest = accountRepository.createAccount("Idemp Dest", AccountType.USER, "INR", 2_000L);
        accountRepository.addMember(source.id(), holderUser.id());
        accountRepository.addMember(dest.id(), holderUser.id());

        String key = "transfer-key-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(source.id(), dest.id(), 3_000L, "Payment for services");

        // First execution
        ResponseEntity<Map> firstResponse = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(request, createHeaders(holderToken, key)),
            Map.class
        );

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody().get("status")).isEqualTo("SUCCESS");
        Object entryId = firstResponse.getBody().get("entryId");
        assertThat(entryId).isNotNull();

        // Balances after first transfer: 10,000 - 3,000 = 7,000, 2,000 + 3,000 = 5,000
        assertThat(accountRepository.findById(source.id()).orElseThrow().balance()).isEqualTo(7_000L);
        assertThat(accountRepository.findById(dest.id()).orElseThrow().balance()).isEqualTo(5_000L);

        // Check duplicate count in database
        Integer initialDupCount = jdbcTemplate.queryForObject(
            "SELECT duplicate_count FROM idempotency_keys WHERE user_id = ? AND key = ?",
            Integer.class,
            holderUser.id(),
            key
        );
        assertThat(initialDupCount).isEqualTo(0);

        // Second execution (replay) with exact same key and payload
        ResponseEntity<Map> secondResponse = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(request, createHeaders(holderToken, key)),
            Map.class
        );

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(secondResponse.getBody().get("entryId")).isEqualTo(entryId);

        // Assert money was strictly NOT moved a second time
        assertThat(accountRepository.findById(source.id()).orElseThrow().balance()).isEqualTo(7_000L);
        assertThat(accountRepository.findById(dest.id()).orElseThrow().balance()).isEqualTo(5_000L);

        // Check duplicate count incremented to 1
        Integer updatedDupCount = jdbcTemplate.queryForObject(
            "SELECT duplicate_count FROM idempotency_keys WHERE user_id = ? AND key = ?",
            Integer.class,
            holderUser.id(),
            key
        );
        assertThat(updatedDupCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Same idempotency key with different request payload is rejected with 422 Unprocessable Entity")
    void testSameKeyDifferentPayloadRejectedWith422() {
        Account source = accountRepository.createAccount("Payload Mismatch Src", AccountType.USER, "INR", 10_000L);
        Account dest = accountRepository.createAccount("Payload Mismatch Dest", AccountType.USER, "INR", 1_000L);
        accountRepository.addMember(source.id(), holderUser.id());

        String key = "mismatch-key-" + UUID.randomUUID();
        TransferRequest originalReq = new TransferRequest(source.id(), dest.id(), 2_000L, "Initial payload");

        ResponseEntity<Map> firstRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(originalReq, createHeaders(holderToken, key)),
            Map.class
        );
        assertThat(firstRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountRepository.findById(source.id()).orElseThrow().balance()).isEqualTo(8_000L);

        // Attempt reuse of same key with different amount (4,000L instead of 2,000L)
        TransferRequest modifiedReq = new TransferRequest(source.id(), dest.id(), 4_000L, "Tampered payload");

        ResponseEntity<Map> secondRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(modifiedReq, createHeaders(holderToken, key)),
            Map.class
        );

        assertThat(secondRes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondRes.getBody().get("message").toString()).contains("different request payload");

        // Verify balance was untouched
        assertThat(accountRepository.findById(source.id()).orElseThrow().balance()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("Replaying a refused request returns the identical refusal without inserting a new attempt row")
    void testRefusedReplayReturnsSameRefusalWithoutNewAttempt() {
        Account source = accountRepository.createAccount("Refusal Test Src", AccountType.USER, "INR", 1_000L);
        Account dest = accountRepository.createAccount("Refusal Test Dest", AccountType.USER, "INR", 0L);
        accountRepository.addMember(source.id(), holderUser.id());

        String key = "refusal-key-" + UUID.randomUUID();
        // Request exceeds balance (5,000 requested vs 1,000 balance)
        TransferRequest req = new TransferRequest(source.id(), dest.id(), 5_000L, "Overdraw attempt");

        ResponseEntity<Map> firstRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(req, createHeaders(holderToken, key)),
            Map.class
        );

        assertThat(firstRes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(firstRes.getBody().get("status")).isEqualTo("REFUSED");
        assertThat(firstRes.getBody().get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");
        Number attemptId = (Number) firstRes.getBody().get("attemptId");
        assertThat(attemptId).isNotNull();

        Integer attemptsCount1 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_attempts WHERE idempotency_key = ?",
            Integer.class,
            key
        );
        assertThat(attemptsCount1).isEqualTo(1);

        // Replay with exact same key
        ResponseEntity<Map> secondRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(req, createHeaders(holderToken, key)),
            Map.class
        );

        assertThat(secondRes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(secondRes.getBody().get("status")).isEqualTo("REFUSED");
        assertThat(secondRes.getBody().get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(secondRes.getBody().get("attemptId")).isEqualTo(attemptId);

        // Verify no second row was added to ledger_attempts
        Integer attemptsCount2 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_attempts WHERE idempotency_key = ?",
            Integer.class,
            key
        );
        assertThat(attemptsCount2).isEqualTo(1);
    }

    @Test
    @DisplayName("Statement endpoint returns postings and refusals in time order with running balances, and enforces authorization")
    void testStatementEndpointAndAuthorization() {
        Account acc = accountRepository.createAccount("Statement Account", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc.id(), holderUser.id());

        // 1. Deposit 5,000 paise
        String depositKey = "dep-" + UUID.randomUUID();
        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(5_000L, "Initial funding"), createHeaders(holderToken, depositKey)),
            Map.class
        );

        // 2. Trigger an overdrawing refusal (withdraw 10,000 paise)
        String refusedKey = "ref-" + UUID.randomUUID();
        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Failed withdrawal"), createHeaders(holderToken, refusedKey)),
            Map.class
        );

        // 3. Successful withdrawal of 2,000 paise
        String withdrawKey = "wth-" + UUID.randomUUID();
        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(2_000L, "Valid withdrawal"), createHeaders(holderToken, withdrawKey)),
            Map.class
        );

        // Call Statement endpoint as member (holder)
        ResponseEntity<StatementResponse> response = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/statement?page=0&size=20",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            StatementResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StatementResponse statement = response.getBody();
        assertThat(statement).isNotNull();
        assertThat(statement.entries()).hasSize(3);

        // Verify entries in chronological order
        // Entry 1: Deposit (POSTING)
        assertThat(statement.entries().get(0).type()).isEqualTo("POSTING");
        assertThat(statement.entries().get(0).amount()).isEqualTo(5_000L);
        assertThat(statement.entries().get(0).runningBalance()).isEqualTo(5_000L);
        assertThat(statement.entries().get(0).initiatorUsername()).isEqualTo("holder");
        assertThat(statement.entries().get(0).entryId()).isNotNull();

        // Entry 2: Refused Attempt (REFUSED)
        assertThat(statement.entries().get(1).type()).isEqualTo("REFUSED");
        assertThat(statement.entries().get(1).amount()).isEqualTo(10_000L);
        assertThat(statement.entries().get(1).runningBalance()).isEqualTo(5_000L); // snapshot of balance at refusal
        assertThat(statement.entries().get(1).initiatorUsername()).isEqualTo("holder");
        assertThat(statement.entries().get(1).entryId()).isNull();

        // Entry 3: Valid Withdrawal (POSTING)
        assertThat(statement.entries().get(2).type()).isEqualTo("POSTING");
        assertThat(statement.entries().get(2).amount()).isEqualTo(-2_000L);
        assertThat(statement.entries().get(2).runningBalance()).isEqualTo(3_000L);
        assertThat(statement.entries().get(2).initiatorUsername()).isEqualTo("holder");
        assertThat(statement.entries().get(2).entryId()).isNotNull();

        // Authorisation checks:
        // A. Auditor CAN access statement
        ResponseEntity<StatementResponse> auditorRes = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/statement",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(auditorToken, null)),
            StatementResponse.class
        );
        assertThat(auditorRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // B. Admin CAN access statement
        ResponseEntity<StatementResponse> adminRes = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/statement",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            StatementResponse.class
        );
        assertThat(adminRes.getStatusCode()).isEqualTo(HttpStatus.OK);

        // C. Non-member holder CANNOT access statement -> receives 404 Not Found
        Account privateAcc = accountRepository.createAccount("Private Admin Account", AccountType.USER, "INR", 100L);
        accountRepository.addMember(privateAcc.id(), adminUser.id());

        ResponseEntity<Map> unauthorizedRes = restTemplate.exchange(
            "/api/accounts/" + privateAcc.id() + "/statement",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(unauthorizedRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Cryptographic hash chains for audit_log and ledger_entries verify correctly and detect tampering")
    void testAuditAndLedgerHashChainVerificationAndTamperDetection() {
        cleanTables();

        // Populate chains with known sequence
        Account acc1 = accountRepository.createAccount("Chain Acc 1", AccountType.USER, "INR", 0L);
        Account acc2 = accountRepository.createAccount("Chain Acc 2", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc1.id(), holderUser.id());
        accountRepository.addMember(acc2.id(), holderUser.id());

        // Deposit
        restTemplate.exchange(
            "/api/accounts/" + acc1.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Initial funding"), createHeaders(holderToken, "chain-dep-" + UUID.randomUUID())),
            Map.class
        );

        // Transfer
        restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(acc1.id(), acc2.id(), 3_000L, "Chain transfer"), createHeaders(holderToken, "chain-tx-" + UUID.randomUUID())),
            Map.class
        );

        // Refusal
        restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(acc1.id(), acc2.id(), 50_000L, "Excessive transfer"), createHeaders(holderToken, "chain-ref-" + UUID.randomUUID())),
            Map.class
        );

        // 1. Verify that both chains pass verification on normal workload
        auditService.verifyAuditLogChain();
        ledgerService.verifyLedgerEntriesChain();

        // 2. Fetch an audit row to tamper with
        Long auditRowId = jdbcTemplate.queryForObject("SELECT id FROM audit_log ORDER BY id DESC LIMIT 1", Long.class);
        assertThat(auditRowId).isNotNull();

        String originalDetails = jdbcTemplate.queryForObject("SELECT details::text FROM audit_log WHERE id = ?", String.class, auditRowId);

        try {
            // Superuser bypasses triggers to modify data
            jdbcTemplate.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_no_update_delete_audit_log");
            jdbcTemplate.update("UPDATE audit_log SET details = '{\"tampered\":true}'::jsonb WHERE id = ?", auditRowId);
            jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_no_update_delete_audit_log");

            // Verification MUST detect payload tampering
            assertThatThrownBy(() -> auditService.verifyAuditLogChain())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("tampering violation at entry ID " + auditRowId);
        } finally {
            // Revert tampering
            jdbcTemplate.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_no_update_delete_audit_log");
            jdbcTemplate.update("UPDATE audit_log SET details = ?::jsonb WHERE id = ?", originalDetails, auditRowId);
            jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_no_update_delete_audit_log");
        }

        // Verify audit log chain passes again after restoring
        auditService.verifyAuditLogChain();

        // 3. Test tampering on ledger_entries hash chain
        Long ledgerEntryId = jdbcTemplate.queryForObject("SELECT id FROM ledger_entries ORDER BY id DESC LIMIT 1", Long.class);
        assertThat(ledgerEntryId).isNotNull();
        String originalReason = jdbcTemplate.queryForObject("SELECT reason FROM ledger_entries WHERE id = ?", String.class, ledgerEntryId);

        try {
            jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_no_update_delete_ledger_entries");
            jdbcTemplate.update("UPDATE ledger_entries SET reason = 'tampered fraudulent reason' WHERE id = ?", ledgerEntryId);
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_no_update_delete_ledger_entries");

            // Verification MUST detect ledger tampering
            assertThatThrownBy(() -> ledgerService.verifyLedgerEntriesChain())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("tampering violation at entry ID " + ledgerEntryId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_no_update_delete_ledger_entries");
            jdbcTemplate.update("UPDATE ledger_entries SET reason = ? WHERE id = ?", originalReason, ledgerEntryId);
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_no_update_delete_ledger_entries");
        }

        // Verify ledger entries chain passes again after restoring
        ledgerService.verifyLedgerEntriesChain();
    }

    private void cleanTables() {
        jdbcTemplate.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_no_update_delete_audit_log");
        jdbcTemplate.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_no_delete_stmt_audit_log");
        jdbcTemplate.execute("DELETE FROM audit_log");
        jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_no_update_delete_audit_log");
        jdbcTemplate.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_no_delete_stmt_audit_log");

        jdbcTemplate.execute("ALTER TABLE postings DISABLE TRIGGER trg_no_update_delete_postings");
        jdbcTemplate.execute("ALTER TABLE postings DISABLE TRIGGER trg_no_delete_stmt_postings");
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_no_update_delete_ledger_entries");
        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_no_delete_stmt_ledger_entries");
        jdbcTemplate.execute("DELETE FROM postings");
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_no_update_delete_ledger_entries");
        jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_no_delete_stmt_ledger_entries");
        jdbcTemplate.execute("ALTER TABLE postings ENABLE TRIGGER trg_no_update_delete_postings");
        jdbcTemplate.execute("ALTER TABLE postings ENABLE TRIGGER trg_no_delete_stmt_postings");

        jdbcTemplate.execute("ALTER TABLE ledger_attempts DISABLE TRIGGER trg_no_update_delete_ledger_attempts");
        jdbcTemplate.execute("ALTER TABLE ledger_attempts DISABLE TRIGGER trg_no_delete_stmt_ledger_attempts");
        jdbcTemplate.execute("DELETE FROM ledger_attempts");
        jdbcTemplate.execute("ALTER TABLE ledger_attempts ENABLE TRIGGER trg_no_update_delete_ledger_attempts");
        jdbcTemplate.execute("ALTER TABLE ledger_attempts ENABLE TRIGGER trg_no_delete_stmt_ledger_attempts");

        jdbcTemplate.execute("DELETE FROM idempotency_keys");
    }
}

