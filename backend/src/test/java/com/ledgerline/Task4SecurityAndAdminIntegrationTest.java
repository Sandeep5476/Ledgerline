package com.ledgerline;

import com.ledgerline.dto.*;
import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import com.ledgerline.model.User;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class Task4SecurityAndAdminIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User adminUser;
    private User holderUser;
    private User auditorUser;

    private String adminToken;
    private String holderToken;
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
    @DisplayName("Admin creates account, grants membership, and executes audited adjustment")
    void testAdminAccountCreationMembershipAndAdjustment() {
        // 1. POST /api/admin/accounts
        CreateAccountRequest createReq = new CreateAccountRequest("Salaries Vault");
        ResponseEntity<AccountDto> createRes = restTemplate.exchange(
            "/api/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(createReq, createHeaders(adminToken, null)),
            AccountDto.class
        );

        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountDto createdAccount = createRes.getBody();
        assertThat(createdAccount).isNotNull();
        assertThat(createdAccount.name()).isEqualTo("Salaries Vault");
        assertThat(createdAccount.currency()).isEqualTo("INR");
        assertThat(createdAccount.type()).isEqualTo("USER");
        assertThat(createdAccount.balance()).isEqualTo(0L);

        // 2. POST /api/admin/accounts/{id}/members
        AddMemberRequest memberReq = new AddMemberRequest(holderUser.id());
        ResponseEntity<Map> memberRes = restTemplate.exchange(
            "/api/admin/accounts/" + createdAccount.id() + "/members",
            HttpMethod.POST,
            new HttpEntity<>(memberReq, createHeaders(adminToken, null)),
            Map.class
        );
        assertThat(memberRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accountRepository.isUserMemberOfAccount(createdAccount.id(), holderUser.id())).isTrue();

        // 3. POST /api/admin/accounts/{id}/adjust (Credit 50,000 paise adjustment)
        String adjKey = "adj-" + UUID.randomUUID();
        AdjustRequest adjustReq = new AdjustRequest(50_000L, "CREDIT", "Dispute settlement credit adjustment");
        ResponseEntity<Map> adjustRes = restTemplate.exchange(
            "/api/admin/accounts/" + createdAccount.id() + "/adjust",
            HttpMethod.POST,
            new HttpEntity<>(adjustReq, createHeaders(adminToken, adjKey)),
            Map.class
        );

        assertThat(adjustRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adjustRes.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(adjustRes.getBody().get("kind")).isEqualTo("ADJUSTMENT");

        Account updated = accountRepository.findById(createdAccount.id()).orElseThrow();
        assertThat(updated.balance()).isEqualTo(50_000L);

        // 4. Debit adjustment respects non-negative rule: attempt to overdraw with 60,000 debit
        String debitOverdrawKey = "adj-overdraw-" + UUID.randomUUID();
        AdjustRequest overdrawReq = new AdjustRequest(60_000L, "DEBIT", "Overdrawing debit adjustment");
        ResponseEntity<Map> overdrawRes = restTemplate.exchange(
            "/api/admin/accounts/" + createdAccount.id() + "/adjust",
            HttpMethod.POST,
            new HttpEntity<>(overdrawReq, createHeaders(adminToken, debitOverdrawKey)),
            Map.class
        );
        assertThat(overdrawRes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overdrawRes.getBody().get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");

        // Verify balance was untouched
        assertThat(accountRepository.findById(createdAccount.id()).orElseThrow().balance()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("HOLDER A cannot read or move from B's account (returns 404, refusal recorded), but sending money TO B is permitted")
    void testHolderCannotAccessUnownedAccountReturns404() {
        // Create unowned account for Admin
        Account adminAcc = accountRepository.createAccount("Secret Admin Vault", AccountType.USER, "INR", 10_000L);
        accountRepository.addMember(adminAcc.id(), adminUser.id());

        // Create owned account for Holder
        Account holderAcc = accountRepository.createAccount("Holder Main", AccountType.USER, "INR", 5_000L);
        accountRepository.addMember(holderAcc.id(), holderUser.id());

        // 1. GET /api/accounts/{id} on unowned account returns 404
        ResponseEntity<Map> balanceRes = restTemplate.exchange(
            "/api/accounts/" + adminAcc.id(),
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(balanceRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 2. GET /api/accounts/{id}/statement on unowned account returns 404
        ResponseEntity<Map> statementRes = restTemplate.exchange(
            "/api/accounts/" + adminAcc.id() + "/statement",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(statementRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 3. POST /api/accounts/{id}/withdraw on unowned account returns 404 and records refusal
        String unauthWithdrawKey = "unauth-wth-" + UUID.randomUUID();
        ResponseEntity<Map> withdrawRes = restTemplate.exchange(
            "/api/accounts/" + adminAcc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(1_000L, "Steal attempt"), createHeaders(holderToken, unauthWithdrawKey)),
            Map.class
        );
        assertThat(withdrawRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Refusal must be recorded as data in ledger_attempts
        Integer refusalCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_attempts WHERE idempotency_key = ? AND status = 'REFUSED'",
            Integer.class,
            unauthWithdrawKey
        );
        assertThat(refusalCount).isEqualTo(1);

        // 4. POST /api/transfers from unowned account returns 404
        String unauthTransferKey = "unauth-tx-" + UUID.randomUUID();
        TransferRequest unauthTx = new TransferRequest(adminAcc.id(), holderAcc.id(), 1_000L, "Illicit transfer");
        ResponseEntity<Map> txRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(unauthTx, createHeaders(holderToken, unauthTransferKey)),
            Map.class
        );
        assertThat(txRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 5. Sending money TO unowned account B by holder A is ALLOWED
        String legitTransferKey = "legit-tx-" + UUID.randomUUID();
        TransferRequest legitTx = new TransferRequest(holderAcc.id(), adminAcc.id(), 2_000L, "Paying counterparty B");
        ResponseEntity<Map> legitRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(legitTx, createHeaders(holderToken, legitTransferKey)),
            Map.class
        );
        assertThat(legitRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legitRes.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(accountRepository.findById(holderAcc.id()).orElseThrow().balance()).isEqualTo(3_000L);
        assertThat(accountRepository.findById(adminAcc.id()).orElseThrow().balance()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("AUDITOR is strictly read-only: every POST write route returns 403 Forbidden")
    void testAuditorHasNoWriteRoutesExpects403() {
        Account targetAcc = accountRepository.createAccount("Auditor Test Acc", AccountType.USER, "INR", 5_000L);

        // 1. POST /api/admin/accounts -> 403
        ResponseEntity<Map> r1 = restTemplate.exchange(
            "/api/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(new CreateAccountRequest("Hacked"), createHeaders(auditorToken, null)),
            Map.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. POST /api/admin/accounts/{id}/members -> 403
        ResponseEntity<Map> r2 = restTemplate.exchange(
            "/api/admin/accounts/" + targetAcc.id() + "/members",
            HttpMethod.POST,
            new HttpEntity<>(new AddMemberRequest(1L), createHeaders(auditorToken, null)),
            Map.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 3. POST /api/admin/accounts/{id}/adjust -> 403
        ResponseEntity<Map> r3 = restTemplate.exchange(
            "/api/admin/accounts/" + targetAcc.id() + "/adjust",
            HttpMethod.POST,
            new HttpEntity<>(new AdjustRequest(1_000L, "CREDIT", "Hacked adj"), createHeaders(auditorToken, "k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 4. POST /api/admin/entries/{id}/reverse -> 403
        ResponseEntity<Map> r4 = restTemplate.exchange(
            "/api/admin/entries/1/reverse",
            HttpMethod.POST,
            new HttpEntity<>(new ReverseRequest("Hacked rev"), createHeaders(auditorToken, "k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 5. POST /api/accounts/{id}/deposit -> 403
        ResponseEntity<Map> r5 = restTemplate.exchange(
            "/api/accounts/" + targetAcc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(1_000L, "Hacked dep"), createHeaders(auditorToken, "k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6. POST /api/accounts/{id}/withdraw -> 403
        ResponseEntity<Map> r6 = restTemplate.exchange(
            "/api/accounts/" + targetAcc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(1_000L, "Hacked wth"), createHeaders(auditorToken, "k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r6.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 7. POST /api/transfers -> 403
        ResponseEntity<Map> r7 = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(targetAcc.id(), 1L, 500L, "Hacked tx"), createHeaders(auditorToken, "k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r7.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Verify AUDITOR CAN read:
        // A. GET /api/accounts -> 200
        ResponseEntity<List> rRead1 = restTemplate.exchange(
            "/api/accounts",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(auditorToken, null)),
            List.class
        );
        assertThat(rRead1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // B. GET /api/admin/verify -> 200
        ResponseEntity<SystemVerificationReport> rRead2 = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(auditorToken, null)),
            SystemVerificationReport.class
        );
        assertThat(rRead2.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Input validation matrix rejects negative, decimals, strings, unknown JSON fields, and malformed JSON with 400")
    void testInputValidationMatrix() {
        Account acc = accountRepository.createAccount("Validation Acc", AccountType.USER, "INR", 10_000L);
        accountRepository.addMember(acc.id(), holderUser.id());

        // 1. Unknown JSON fields rejected with 400
        String unknownFieldJson = "{\"amount\": 1000, \"reason\": \"Valid\", \"role\": \"ADMIN\"}";
        HttpHeaders headers = createHeaders(holderToken, "val-k-" + UUID.randomUUID());
        ResponseEntity<Map> r1 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(unknownFieldJson, headers),
            Map.class
        );
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2. Floating point amount (decimals) rejected with 400
        String decimalAmountJson = "{\"amount\": 100.5, \"reason\": \"Decimals not allowed\"}";
        ResponseEntity<Map> r2 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(decimalAmountJson, createHeaders(holderToken, "val-k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 3. String amount (type confusion) rejected with 400
        String stringAmountJson = "{\"amount\": \"1000\", \"reason\": \"String not allowed\"}";
        ResponseEntity<Map> r3 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(stringAmountJson, createHeaders(holderToken, "val-k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 4. Same-account transfer rejected with 400
        TransferRequest sameAccReq = new TransferRequest(acc.id(), acc.id(), 500L, "Self transfer");
        ResponseEntity<Map> r4 = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(sameAccReq, createHeaders(holderToken, "val-k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 5. Malformed JSON returns 400 generic error
        String malformedJson = "{\"amount\": ";
        ResponseEntity<Map> r5 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(malformedJson, createHeaders(holderToken, "val-k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 6. Invalid path variable type returns 400
        ResponseEntity<Map> r6 = restTemplate.exchange(
            "/api/accounts/not-a-number/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(100L, "test"), createHeaders(holderToken, "val-k-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(r6.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Security headers (CSP, nosniff, DENY, Referrer-Policy) are present on responses")
    void testSecurityHeadersPresent() {
        ResponseEntity<String> res = restTemplate.getForEntity("/actuator/health", String.class);
        HttpHeaders headers = res.getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'self'");
    }

    @Test
    @DisplayName("GET /api/admin/verify passes on valid state and reports FAILED when superuser tampers with data")
    void testVerifyEndpointPassesAndDetectsTampering() {
        cleanTables();

        // Populate system with valid ledger activity
        Account accA = accountRepository.createAccount("Verify Acc A", AccountType.USER, "INR", 0L);
        Account accB = accountRepository.createAccount("Verify Acc B", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accA.id(), holderUser.id());
        accountRepository.addMember(accB.id(), holderUser.id());

        // Deposit into accA
        restTemplate.exchange(
            "/api/accounts/" + accA.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Initial funding"), createHeaders(holderToken, "v-dep-" + UUID.randomUUID())),
            Map.class
        );

        // Transfer from accA to accB
        restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(accA.id(), accB.id(), 4_000L, "Transfer funding"), createHeaders(holderToken, "v-tx-" + UUID.randomUUID())),
            Map.class
        );

        // 1. Call verify on valid state -> 200 OK, overall status PASSED
        ResponseEntity<SystemVerificationReport> initialRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );

        assertThat(initialRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        SystemVerificationReport report = initialRes.getBody();
        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.checks().get("allPostingsSumZero").status()).isEqualTo("PASS");
        assertThat(report.checks().get("cachedBalancesMatchPostings").status()).isEqualTo("PASS");
        assertThat(report.checks().get("netExternalFlowMatchesUserBalances").status()).isEqualTo("PASS");
        assertThat(report.checks().get("noNegativeUserBalances").status()).isEqualTo("PASS");
        assertThat(report.checks().get("hashChainsIntact").status()).isEqualTo("PASS");
        assertThat(report.checks().get("everyEntryPostingsBalanced").status()).isEqualTo("PASS");

        // 2. Superuser alters an account balance directly in the database
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 99999 WHERE id = ?", accA.id());

        // 3. Call verify again -> reports FAILED with detected balance mismatch
        ResponseEntity<SystemVerificationReport> tamperedRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );

        assertThat(tamperedRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        SystemVerificationReport tamperedReport = tamperedRes.getBody();
        assertThat(tamperedReport).isNotNull();
        assertThat(tamperedReport.status()).isEqualTo("FAILED");
        assertThat(tamperedReport.checks().get("cachedBalancesMatchPostings").status()).isEqualTo("FAIL");
        assertThat(tamperedReport.checks().get("netExternalFlowMatchesUserBalances").status()).isEqualTo("FAIL");

        // Revert tampering
        jdbcTemplate.update("UPDATE accounts SET balance = balance - 99999 WHERE id = ?", accA.id());

        // Call verify again -> PASSED
        ResponseEntity<SystemVerificationReport> restoredRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );
        assertThat(restoredRes.getBody().status()).isEqualTo("PASSED");
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
        jdbcTemplate.execute("UPDATE accounts SET balance = 0");
    }

    @Test
    @DisplayName("Rate limiter throttles excessive requests and returns 429 with Retry-After")
    void testRateLimiterTriggers429WithRetryAfter() {
        // Repeated login requests for a specific user to exhaust the 5-requests-per-minute user limit
        LoginRequest loginReq = new LoginRequest("rate-limited-user", "WrongPassword_123");
        ResponseEntity<Map> lastRes = null;

        for (int i = 0; i < 7; i++) {
            lastRes = restTemplate.postForEntity("/api/auth/login", loginReq, Map.class);
            if (lastRes.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                break;
            }
        }

        assertThat(lastRes).isNotNull();
        assertThat(lastRes.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lastRes.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(lastRes.getBody().get("message").toString()).contains("Too many login attempts");
    }
}

