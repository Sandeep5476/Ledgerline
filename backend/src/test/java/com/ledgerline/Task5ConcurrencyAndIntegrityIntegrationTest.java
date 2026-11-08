package com.ledgerline;

import com.ledgerline.dto.*;
import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import com.ledgerline.model.User;
import com.ledgerline.model.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import com.ledgerline.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class Task5ConcurrencyAndIntegrityIntegrationTest extends BasePostgresIntegrationTest {

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

    @Autowired
    private RateLimiterService rateLimiterService;

    private User adminUser;
    private User holderUser;
    private User auditorUser;
    private User secondHolderUser;

    private String adminToken;
    private String holderToken;
    private String auditorToken;
    private String secondHolderToken;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        rateLimiterService.reset();

        adminUser = userRepository.findByUsername("admin").orElseThrow();
        holderUser = userRepository.findByUsername("holder").orElseThrow();
        auditorUser = userRepository.findByUsername("auditor").orElseThrow();

        secondHolderUser = userRepository.findByUsername("holder2").orElseGet(() -> {
            return userRepository.create("holder2", "$2a$12$e8pYwE93/6K5g9WqPevzveA0wF8M9H7J1p2v3q4r5s6t7u8v9w0x.", UserRole.HOLDER);
        });

        adminToken = jwtService.generateToken(adminUser);
        holderToken = jwtService.generateToken(holderUser);
        auditorToken = jwtService.generateToken(auditorUser);
        secondHolderToken = jwtService.generateToken(secondHolderUser);
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
        rateLimiterService.reset();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 1. CONCURRENT OVERDRAW: balance 100.00; 20 threads withdraw 60.00 with DIFFERENT keys. Exactly 1 succeeds, 19 refused.
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("1. Concurrent overdraw: 20 threads withdraw 60.00 from 100.00 balance; exactly 1 succeeds, 19 refused")
    void test1_ConcurrentOverdraw() throws Exception {
        cleanTables();

        Account acc = accountRepository.createAccount("Acc-Overdraw", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc.id(), holderUser.id());

        // Fund account with 100.00 INR (10,000 paise)
        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Initial funding 100 INR"), createHeaders(holderToken, "fund-1-" + UUID.randomUUID())),
            Map.class
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String key = "withdraw-key-" + i + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startGun.await();
                return restTemplate.exchange(
                    "/api/accounts/" + acc.id() + "/withdraw",
                    HttpMethod.POST,
                    new HttpEntity<>(new AmountRequest(6_000L, "Concurrent withdraw 60 INR"), createHeaders(holderToken, key)),
                    Map.class
                );
            }));
        }

        // Fire starting gun
        startGun.countDown();

        int successCount = 0;
        int refusedCount = 0;

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(15, TimeUnit.SECONDS);
            Map body = res.getBody();
            assertThat(body).isNotNull();
            if (res.getStatusCode() == HttpStatus.OK) {
                successCount++;
                assertThat(body.get("status")).isEqualTo("SUCCESS");
            } else if (res.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                refusedCount++;
                assertThat(body.get("status")).isEqualTo("REFUSED");
                assertThat(body.get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(refusedCount).isEqualTo(19);

        // Verify balance is 40.00 INR (4,000 paise)
        Account updated = accountRepository.findById(acc.id()).orElseThrow();
        assertThat(updated.balance()).isEqualTo(4_000L);

        // Verify system integrity passes
        ResponseEntity<SystemVerificationReport> verifyRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );
        assertThat(verifyRes.getBody().status()).isEqualTo("PASSED");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 2. CONCURRENT TRANSFER OVERDRAW: same via transfers to another account
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("2. Concurrent transfer overdraw: 20 threads transfer 60.00 from 100.00 balance; exactly 1 succeeds, 19 refused")
    void test2_ConcurrentTransferOverdraw() throws Exception {
        cleanTables();

        Account accFrom = accountRepository.createAccount("Acc-Transfer-From", AccountType.USER, "INR", 0L);
        Account accTo = accountRepository.createAccount("Acc-Transfer-To", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accFrom.id(), holderUser.id());
        accountRepository.addMember(accTo.id(), secondHolderUser.id());

        // Fund accFrom with 100.00 INR (10,000 paise)
        restTemplate.exchange(
            "/api/accounts/" + accFrom.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Initial funding 100 INR"), createHeaders(holderToken, "fund-tx-" + UUID.randomUUID())),
            Map.class
        );

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String key = "transfer-key-" + i + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startGun.await();
                return restTemplate.exchange(
                    "/api/transfers",
                    HttpMethod.POST,
                    new HttpEntity<>(new TransferRequest(accFrom.id(), accTo.id(), 6_000L, "Concurrent transfer 60 INR"), createHeaders(holderToken, key)),
                    Map.class
                );
            }));
        }

        startGun.countDown();

        int successCount = 0;
        int refusedCount = 0;

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(15, TimeUnit.SECONDS);
            Map body = res.getBody();
            assertThat(body).isNotNull();
            if (res.getStatusCode() == HttpStatus.OK) {
                successCount++;
                assertThat(body.get("status")).isEqualTo("SUCCESS");
            } else if (res.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                refusedCount++;
                assertThat(body.get("status")).isEqualTo("REFUSED");
                assertThat(body.get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");
            }
        }
        executor.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(refusedCount).isEqualTo(19);

        assertThat(accountRepository.findById(accFrom.id()).orElseThrow().balance()).isEqualTo(4_000L);
        assertThat(accountRepository.findById(accTo.id()).orElseThrow().balance()).isEqualTo(6_000L);

        ResponseEntity<SystemVerificationReport> verifyRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );
        assertThat(verifyRes.getBody().status()).isEqualTo("PASSED");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 3. OPPOSITE-DIRECTION TRANSFERS: A->B and B->A, 50 threads each way, small amounts, no deadlock, conserved
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("3. Opposite-direction transfers: 50 threads A->B and 50 threads B->A; no deadlock, balance conserved")
    void test3_OppositeDirectionTransfersNoDeadlock() throws Exception {
        cleanTables();

        Account accA = accountRepository.createAccount("Acc-Opp-A", AccountType.USER, "INR", 0L);
        Account accB = accountRepository.createAccount("Acc-Opp-B", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accA.id(), holderUser.id());
        accountRepository.addMember(accB.id(), holderUser.id());

        // Fund each account with 100,000 paise (1,000.00 INR). Total system = 200,000 paise.
        restTemplate.exchange(
            "/api/accounts/" + accA.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(100_000L, "Fund A"), createHeaders(holderToken, "fund-opp-a-" + UUID.randomUUID())),
            Map.class
        );
        restTemplate.exchange(
            "/api/accounts/" + accB.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(100_000L, "Fund B"), createHeaders(holderToken, "fund-opp-b-" + UUID.randomUUID())),
            Map.class
        );

        int totalThreads = 100; // 50 A->B, 50 B->A
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            final String keyA = "opp-ab-" + i + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startGun.await();
                return restTemplate.exchange(
                    "/api/transfers",
                    HttpMethod.POST,
                    new HttpEntity<>(new TransferRequest(accA.id(), accB.id(), 100L, "A to B"), createHeaders(holderToken, keyA)),
                    Map.class
                );
            }));

            final String keyB = "opp-ba-" + i + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startGun.await();
                return restTemplate.exchange(
                    "/api/transfers",
                    HttpMethod.POST,
                    new HttpEntity<>(new TransferRequest(accB.id(), accA.id(), 100L, "B to A"), createHeaders(holderToken, keyB)),
                    Map.class
                );
            }));
        }

        startGun.countDown();

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(20, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status")).isEqualTo("SUCCESS");
        }
        executor.shutdown();

        Account finalA = accountRepository.findById(accA.id()).orElseThrow();
        Account finalB = accountRepository.findById(accB.id()).orElseThrow();

        assertThat(finalA.balance()).isEqualTo(100_000L);
        assertThat(finalB.balance()).isEqualTo(100_000L);
        assertThat(finalA.balance() + finalB.balance()).isEqualTo(200_000L);

        ResponseEntity<SystemVerificationReport> verifyRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );
        assertThat(verifyRes.getBody().status()).isEqualTo("PASSED");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 4. MANY-TO-ONE and ONE-TO-MANY fan-in/fan-out with a ring of 5 accounts
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("4. Fan-in and Fan-out with ring of 5 accounts; money conserved, no deadlocks")
    void test4_FanInFanOutRingAccounts() throws Exception {
        cleanTables();

        List<Account> ring = new ArrayList<>();
        long initialBalancePerAccount = 20_000L;
        long totalInitial = initialBalancePerAccount * 5;

        for (int i = 0; i < 5; i++) {
            Account acc = accountRepository.createAccount("Ring-" + i, AccountType.USER, "INR", 0L);
            accountRepository.addMember(acc.id(), holderUser.id());
            restTemplate.exchange(
                "/api/accounts/" + acc.id() + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(initialBalancePerAccount, "Fund ring " + i), createHeaders(holderToken, "fund-ring-" + i + "-" + UUID.randomUUID())),
                Map.class
            );
            ring.add(acc);
        }

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        // Ring transfers: 0->1, 1->2, 2->3, 3->4, 4->0 (10 times each)
        for (int cycle = 0; cycle < 10; cycle++) {
            for (int i = 0; i < 5; i++) {
                final Long fromId = ring.get(i).id();
                final Long toId = ring.get((i + 1) % 5).id();
                final String key = "ring-" + cycle + "-" + i + "-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    startGun.await();
                    return restTemplate.exchange(
                        "/api/transfers",
                        HttpMethod.POST,
                        new HttpEntity<>(new TransferRequest(fromId, toId, 50L, "Ring transfer"), createHeaders(holderToken, key)),
                        Map.class
                    );
                }));
            }
        }

        // Fan-in: 1, 2, 3, 4 -> 0 (5 times each)
        for (int rep = 0; rep < 5; rep++) {
            for (int i = 1; i < 5; i++) {
                final Long fromId = ring.get(i).id();
                final Long toId = ring.get(0).id();
                final String key = "fanin-" + rep + "-" + i + "-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    startGun.await();
                    return restTemplate.exchange(
                        "/api/transfers",
                        HttpMethod.POST,
                        new HttpEntity<>(new TransferRequest(fromId, toId, 100L, "Fan-in transfer"), createHeaders(holderToken, key)),
                        Map.class
                    );
                }));
            }
        }

        // Fan-out: 0 -> 1, 2, 3, 4 (5 times each)
        for (int rep = 0; rep < 5; rep++) {
            for (int i = 1; i < 5; i++) {
                final Long fromId = ring.get(0).id();
                final Long toId = ring.get(i).id();
                final String key = "fanout-" + rep + "-" + i + "-" + UUID.randomUUID();
                futures.add(executor.submit(() -> {
                    startGun.await();
                    return restTemplate.exchange(
                        "/api/transfers",
                        HttpMethod.POST,
                        new HttpEntity<>(new TransferRequest(fromId, toId, 100L, "Fan-out transfer"), createHeaders(holderToken, key)),
                        Map.class
                    );
                }));
            }
        }

        startGun.countDown();

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(20, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("status")).isEqualTo("SUCCESS");
        }
        executor.shutdown();

        long sumBalances = 0;
        for (Account a : ring) {
            long bal = accountRepository.findById(a.id()).orElseThrow().balance();
            assertThat(bal).isGreaterThan(0L);
            sumBalances += bal;
        }

        assertThat(sumBalances).isEqualTo(totalInitial);

        ResponseEntity<SystemVerificationReport> verifyRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );
        assertThat(verifyRes.getBody().status()).isEqualTo("PASSED");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 5. SAME-KEY REPLAY, CONCURRENT: 20 threads with the SAME key and body. Exactly 1 ledger entry, duplicate_count = 19
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("5. Concurrent same-key replay: 20 threads with same key; exactly 1 entry created, duplicate_count=19")
    void test5_ConcurrentSameKeyReplay() throws Exception {
        cleanTables();

        Account acc = accountRepository.createAccount("Acc-Concurrent-Key", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc.id(), holderUser.id());

        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(100_000L, "Fund 1000 INR"), createHeaders(holderToken, "fund-ck-" + UUID.randomUUID())),
            Map.class
        );

        final String sharedKey = "shared-idempotency-key-" + UUID.randomUUID();
        final AmountRequest withdrawReq = new AmountRequest(5_000L, "Concurrent shared key withdraw");

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startGun.await();
                return restTemplate.exchange(
                    "/api/accounts/" + acc.id() + "/withdraw",
                    HttpMethod.POST,
                    new HttpEntity<>(withdrawReq, createHeaders(holderToken, sharedKey)),
                    Map.class
                );
            }));
        }

        startGun.countDown();

        Long sharedEntryId = null;
        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(15, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map body = res.getBody();
            assertThat(body.get("status")).isEqualTo("SUCCESS");

            Long entryId = ((Number) body.get("entryId")).longValue();
            if (sharedEntryId == null) {
                sharedEntryId = entryId;
            } else {
                assertThat(entryId).isEqualTo(sharedEntryId);
            }
        }
        executor.shutdown();

        // Exactly one ledger entry was created
        Integer entryCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_entries WHERE idempotency_key = ?",
            Integer.class,
            sharedKey
        );
        assertThat(entryCount).isEqualTo(1);

        // duplicate_count is exactly 19
        Integer duplicateCount = jdbcTemplate.queryForObject(
            "SELECT duplicate_count FROM idempotency_keys WHERE user_id = ? AND key = ?",
            Integer.class,
            holderUser.id(),
            sharedKey
        );
        assertThat(duplicateCount).isEqualTo(19);

        // Balance decremented exactly ONCE (95,000 paise)
        assertThat(accountRepository.findById(acc.id()).orElseThrow().balance()).isEqualTo(95_000L);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 6. SAME-KEY SEQUENTIAL REPLAY after success AND after refusal: identical response
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("6. Same-key sequential replay after success and refusal returns identical response")
    void test6_SequentialSameKeyReplaySuccessAndRefusal() {
        cleanTables();

        Account acc = accountRepository.createAccount("Acc-Seq-Replay", AccountType.USER, "INR", 0L);
        accountRepository.addMember(acc.id(), holderUser.id());

        restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Fund 100 INR"), createHeaders(holderToken, "fund-seq-" + UUID.randomUUID())),
            Map.class
        );

        // 1. Success replay
        String successKey = "success-seq-key-" + UUID.randomUUID();
        ResponseEntity<Map> res1 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(2_000L, "Withdraw 20 INR"), createHeaders(holderToken, successKey)),
            Map.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res1.getBody().get("status")).isEqualTo("SUCCESS");
        Long entryId1 = ((Number) res1.getBody().get("entryId")).longValue();

        ResponseEntity<Map> res1Replay = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(2_000L, "Withdraw 20 INR"), createHeaders(holderToken, successKey)),
            Map.class
        );
        assertThat(res1Replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res1Replay.getBody().get("status")).isEqualTo("SUCCESS");
        Long entryId1Replay = ((Number) res1Replay.getBody().get("entryId")).longValue();
        assertThat(entryId1Replay).isEqualTo(entryId1);

        // 2. Refusal replay
        String refusalKey = "refusal-seq-key-" + UUID.randomUUID();
        ResponseEntity<Map> res2 = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(999_000L, "Withdraw way over balance"), createHeaders(holderToken, refusalKey)),
            Map.class
        );
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res2.getBody().get("status")).isEqualTo("REFUSED");
        assertThat(res2.getBody().get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");

        int attemptsBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_attempts WHERE idempotency_key = ?",
            Integer.class,
            refusalKey
        );
        assertThat(attemptsBefore).isEqualTo(1);

        ResponseEntity<Map> res2Replay = restTemplate.exchange(
            "/api/accounts/" + acc.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(999_000L, "Withdraw way over balance"), createHeaders(holderToken, refusalKey)),
            Map.class
        );
        assertThat(res2Replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res2Replay.getBody().get("status")).isEqualTo("REFUSED");
        assertThat(res2Replay.getBody().get("refusalCode")).isEqualTo("INSUFFICIENT_FUNDS");

        // No new attempt created
        int attemptsAfter = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_attempts WHERE idempotency_key = ?",
            Integer.class,
            refusalKey
        );
        assertThat(attemptsAfter).isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 7. SAME KEY, DIFFERENT BODY: 422, no money moved. Same key from a DIFFERENT user is independent
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("7. Same key different body -> 422; same key from different user succeeds independently")
    void test7_SameKeyDifferentBodyAndDifferentUser() {
        cleanTables();

        Account accA = accountRepository.createAccount("Acc-Diff-Body-A", AccountType.USER, "INR", 0L);
        Account accB = accountRepository.createAccount("Acc-Diff-Body-B", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accA.id(), holderUser.id());
        accountRepository.addMember(accB.id(), secondHolderUser.id());

        String sharedKey = "cross-user-test-key-" + UUID.randomUUID();

        // 1. HolderUser deposits 5,000 paise with sharedKey
        ResponseEntity<Map> res1 = restTemplate.exchange(
            "/api/accounts/" + accA.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(5_000L, "Initial 50 INR"), createHeaders(holderToken, sharedKey)),
            Map.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res1.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(accountRepository.findById(accA.id()).orElseThrow().balance()).isEqualTo(5_000L);

        // 2. HolderUser reuses SAME key with DIFFERENT amount (10,000 paise) -> 422 Unprocessable Entity
        ResponseEntity<Map> resTampered = restTemplate.exchange(
            "/api/accounts/" + accA.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Altered amount"), createHeaders(holderToken, sharedKey)),
            Map.class
        );
        assertThat(resTampered.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // Balance remains unchanged at 5,000 paise
        assertThat(accountRepository.findById(accA.id()).orElseThrow().balance()).isEqualTo(5_000L);

        // 3. SecondHolderUser uses the SAME sharedKey on accB -> 200 OK (scoped per user!)
        ResponseEntity<Map> resOtherUser = restTemplate.exchange(
            "/api/accounts/" + accB.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(8_000L, "Second holder deposit"), createHeaders(secondHolderToken, sharedKey)),
            Map.class
        );
        assertThat(resOtherUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resOtherUser.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(accountRepository.findById(accB.id()).orElseThrow().balance()).isEqualTo(8_000L);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 8. AUTHORISATION: A cannot move from B (404 + REFUSED), cannot read B statement; AUDITOR cannot write; HOLDER cannot hit admin
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("8. Authorisation: A cannot move/read B; AUDITOR cannot write; HOLDER cannot hit admin routes")
    void test8_AuthorisationConstraints() {
        cleanTables();

        Account accA = accountRepository.createAccount("Acc-Auth-A", AccountType.USER, "INR", 0L);
        Account accB = accountRepository.createAccount("Acc-Auth-B", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accA.id(), holderUser.id());
        accountRepository.addMember(accB.id(), secondHolderUser.id());

        // Fund B's account
        restTemplate.exchange(
            "/api/accounts/" + accB.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(10_000L, "Fund B"), createHeaders(secondHolderToken, "fund-b-" + UUID.randomUUID())),
            Map.class
        );

        // A tries to withdraw from B -> 404 Not Found
        ResponseEntity<Map> withdrawRes = restTemplate.exchange(
            "/api/accounts/" + accB.id() + "/withdraw",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(1_000L, "Steal from B"), createHeaders(holderToken, "steal-w-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(withdrawRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Refused attempt recorded
        Integer refusedAttempts = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_attempts WHERE initiated_by = ? AND refusal_reason LIKE '%authorized%'",
            Integer.class,
            holderUser.id()
        );
        assertThat(refusedAttempts).isGreaterThanOrEqualTo(1);

        // A tries to read B's statement -> 404 Not Found
        ResponseEntity<Map> statementRes = restTemplate.exchange(
            "/api/accounts/" + accB.id() + "/statement",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(statementRes.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // AUDITOR attempts to write -> 403 Forbidden
        ResponseEntity<Map> auditorDeposit = restTemplate.exchange(
            "/api/accounts/" + accA.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(1_000L, "Auditor deposit"), createHeaders(auditorToken, "aud-dep-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(auditorDeposit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> auditorTransfer = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(accA.id(), accB.id(), 1_000L, "Auditor tx"), createHeaders(auditorToken, "aud-tx-" + UUID.randomUUID())),
            Map.class
        );
        assertThat(auditorTransfer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // HOLDER attempts admin routes -> 403 Forbidden
        ResponseEntity<Map> holderAdminAccount = restTemplate.exchange(
            "/api/admin/accounts",
            HttpMethod.POST,
            new HttpEntity<>(new CreateAccountRequest("Hacker Acc"), createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(holderAdminAccount.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> holderAdminVerify = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(holderToken, null)),
            Map.class
        );
        assertThat(holderAdminVerify.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 9. APPEND-ONLY: as ledger_app role through raw JDBC, UPDATE/DELETE/TRUNCATE fails; cannot ALTER TABLE / DISABLE TRIGGER
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("9. Append-only enforcement: ledger_app role cannot UPDATE, DELETE, TRUNCATE, ALTER TABLE or DISABLE TRIGGER")
    void test9_AppendOnlyEnforcementAsAppRole() throws Exception {
        cleanTables();

        // Connect specifically with ledger_app credentials
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "ledger_app", APP_PASSWORD)) {
            conn.setAutoCommit(false);

            // 1. UPDATE on ledger_entries fails
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE ledger_entries SET reason = 'tampered'")) {
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            conn.rollback();

            // 2. DELETE on postings fails
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM postings")) {
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            conn.rollback();

            // 3. TRUNCATE on audit_log fails
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("TRUNCATE TABLE audit_log")) {
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            conn.rollback();

            // 4. ALTER TABLE fails (must be owner)
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("ALTER TABLE ledger_entries ADD COLUMN foo text")) {
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            conn.rollback();

            // 5. DISABLE TRIGGER fails (must be owner)
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = conn.prepareStatement("ALTER TABLE ledger_entries DISABLE TRIGGER ALL")) {
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
            conn.rollback();
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 10. UNBALANCED ENTRY via raw SQL is rejected at commit
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("10. Unbalanced entry via raw SQL is rejected at commit by deferrable zero-sum trigger")
    void test10_UnbalancedEntryRejectedAtCommit() throws Exception {
        cleanTables();

        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS)) {
            conn.setAutoCommit(false);

            Account acc = accountRepository.createAccount("Acc-ZeroSum", AccountType.USER, "INR", 0L);

            long entryId;
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger_entries (kind, initiated_by, idempotency_key, reason) " +
                "VALUES ('TRANSFER', ?, 'raw-unbalanced-key', 'raw unbalanced test') RETURNING id"
            )) {
                ps.setLong(1, holderUser.id());
                var rs = ps.executeQuery();
                rs.next();
                entryId = rs.getLong(1);
            }

            // Insert single unbalanced posting (+500 paise, sum is 500 != 0)
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO postings (entry_id, account_id, amount, balance_after) VALUES (?, ?, 500, 500)"
            )) {
                ps.setLong(1, entryId);
                ps.setLong(2, acc.id());
                ps.executeUpdate();
            }

            // Commit must fail because deferred constraint trigger checks SUM(amount) == 0
            assertThatThrownBy(conn::commit)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("do not balance to zero");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 11. CONSERVATION UNDER RANDOM WORKLOAD: seeded RNG (print seed), 200 mixed operations across 8 threads
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("11. Conservation under random workload: 200 mixed ops across 8 threads; total conserved, verify passes")
    void test11_ConservationUnderRandomWorkload() throws Exception {
        cleanTables();

        long seed = System.currentTimeMillis();
        System.out.println("Random Workload Seed: " + seed);
        Random rng = new Random(seed);

        // Create 4 test accounts
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Account acc = accountRepository.createAccount("RandomAcc-" + i, AccountType.USER, "INR", 0L);
            accountRepository.addMember(acc.id(), holderUser.id());
            // Initial deposit 50,000 paise (500 INR)
            restTemplate.exchange(
                "/api/accounts/" + acc.id() + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(50_000L, "Seed fund " + i), createHeaders(holderToken, "seed-" + i + "-" + UUID.randomUUID())),
                Map.class
            );
            accounts.add(acc);
        }

        int totalOperations = 200;
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < totalOperations; i++) {
            final int opType = rng.nextInt(3); // 0: deposit, 1: withdraw, 2: transfer
            final int fromIdx = rng.nextInt(accounts.size());
            final int toIdx = rng.nextInt(accounts.size());
            final long amount = (rng.nextInt(50) + 1) * 10L; // 10 to 500 paise
            final String key = "random-op-" + i + "-" + UUID.randomUUID();

            futures.add(executor.submit(() -> {
                startGun.await();
                if (opType == 0) {
                    // Deposit
                    return restTemplate.exchange(
                        "/api/accounts/" + accounts.get(fromIdx).id() + "/deposit",
                        HttpMethod.POST,
                        new HttpEntity<>(new AmountRequest(amount, "Random deposit"), createHeaders(holderToken, key)),
                        Map.class
                    );
                } else if (opType == 1) {
                    // Withdraw
                    return restTemplate.exchange(
                        "/api/accounts/" + accounts.get(fromIdx).id() + "/withdraw",
                        HttpMethod.POST,
                        new HttpEntity<>(new AmountRequest(amount, "Random withdraw"), createHeaders(holderToken, key)),
                        Map.class
                    );
                } else {
                    // Transfer
                    Long fromId = accounts.get(fromIdx).id();
                    Long toId = accounts.get(toIdx).id();
                    if (fromId.equals(toId)) {
                        toId = accounts.get((toIdx + 1) % accounts.size()).id();
                    }
                    return restTemplate.exchange(
                        "/api/transfers",
                        HttpMethod.POST,
                        new HttpEntity<>(new TransferRequest(fromId, toId, amount, "Random transfer"), createHeaders(holderToken, key)),
                        Map.class
                    );
                }
            }));
        }

        startGun.countDown();

        for (Future<ResponseEntity<Map>> f : futures) {
            ResponseEntity<Map> res = f.get(25, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            // Result is either SUCCESS or REFUSED (both are normal 200 responses)
            String status = (String) res.getBody().get("status");
            assertThat(status).isIn("SUCCESS", "REFUSED");
        }
        executor.shutdown();

        // Perform system verification
        ResponseEntity<SystemVerificationReport> verifyRes = restTemplate.exchange(
            "/api/admin/verify",
            HttpMethod.GET,
            new HttpEntity<>(createHeaders(adminToken, null)),
            SystemVerificationReport.class
        );

        assertThat(verifyRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        SystemVerificationReport report = verifyRes.getBody();
        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.checks().get("allPostingsSumZero").status()).isEqualTo("PASS");
        assertThat(report.checks().get("cachedBalancesMatchPostings").status()).isEqualTo("PASS");
        assertThat(report.checks().get("netExternalFlowMatchesUserBalances").status()).isEqualTo("PASS");
        assertThat(report.checks().get("noNegativeUserBalances").status()).isEqualTo("PASS");
        assertThat(report.checks().get("hashChainsIntact").status()).isEqualTo("PASS");
        assertThat(report.checks().get("everyEntryPostingsBalanced").status()).isEqualTo("PASS");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // 12. PARTIAL FAILURE: inject failure after postings inserted before commit; assert nothing persisted, retryable key
    // -----------------------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("12. Partial failure injection: failure after postings rolls back completely; idempotency key retryable")
    void test12_PartialFailureRollsBackAndKeyRetryable() {
        cleanTables();

        Account accFrom = accountRepository.createAccount("Acc-Fail-From", AccountType.USER, "INR", 0L);
        Account accTo = accountRepository.createAccount("Acc-Fail-To", AccountType.USER, "INR", 0L);
        accountRepository.addMember(accFrom.id(), holderUser.id());
        accountRepository.addMember(accTo.id(), holderUser.id());

        // Fund accFrom with 50,000 paise
        restTemplate.exchange(
            "/api/accounts/" + accFrom.id() + "/deposit",
            HttpMethod.POST,
            new HttpEntity<>(new AmountRequest(50_000L, "Fund from"), createHeaders(holderToken, "fund-pf-" + UUID.randomUUID())),
            Map.class
        );

        long balanceFromBefore = accountRepository.findById(accFrom.id()).orElseThrow().balance();
        long balanceToBefore = accountRepository.findById(accTo.id()).orElseThrow().balance();
        int entriesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        int postingsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM postings", Integer.class);

        // Inject a database failure on audit_log insert (which happens AFTER postings are inserted in LedgerService)
        jdbcTemplate.execute(
            "CREATE OR REPLACE FUNCTION fail_audit_test() RETURNS TRIGGER AS $$ " +
            "BEGIN " +
            "  IF NEW.details LIKE '%FAIL_TEST%' THEN " +
            "    RAISE EXCEPTION 'Simulated failure after postings inserted'; " +
            "  END IF; " +
            "  RETURN NEW; " +
            "END; $$ LANGUAGE plpgsql;"
        );
        jdbcTemplate.execute(
            "CREATE TRIGGER trg_fail_audit_test BEFORE INSERT ON audit_log " +
            "FOR EACH ROW EXECUTE FUNCTION fail_audit_test();"
        );

        String failureKey = "test-fail-retry-key-" + UUID.randomUUID();

        try {
            // Attempt transfer with FAIL_TEST in reason
            ResponseEntity<Map> failRes = restTemplate.exchange(
                "/api/transfers",
                HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(accFrom.id(), accTo.id(), 10_000L, "Payment FAIL_TEST"), createHeaders(holderToken, failureKey)),
                Map.class
            );

            // Server caught error and returned 500 Internal Server Error (or transaction rollback)
            assertThat(failRes.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

            // Verify NOTHING was persisted: balances, entries, postings unchanged
            long balanceFromAfter = accountRepository.findById(accFrom.id()).orElseThrow().balance();
            long balanceToAfter = accountRepository.findById(accTo.id()).orElseThrow().balance();
            int entriesAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
            int postingsAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM postings", Integer.class);

            assertThat(balanceFromAfter).isEqualTo(balanceFromBefore);
            assertThat(balanceToAfter).isEqualTo(balanceToBefore);
            assertThat(entriesAfter).isEqualTo(entriesBefore);
            assertThat(postingsAfter).isEqualTo(postingsBefore);

            // Verify the idempotency key was rolled back (not left in IN_PROGRESS or COMPLETED)
            int keyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE key = ?",
                Integer.class,
                failureKey
            );
            assertThat(keyCount).isEqualTo(0);

        } finally {
            // Clean up injection trigger
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_audit_test ON audit_log");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_audit_test");
        }

        // Now RETRY with the EXACT SAME idempotency key (normal reason)
        ResponseEntity<Map> retryRes = restTemplate.exchange(
            "/api/transfers",
            HttpMethod.POST,
            new HttpEntity<>(new TransferRequest(accFrom.id(), accTo.id(), 10_000L, "Payment RETRY_SUCCESS"), createHeaders(holderToken, failureKey)),
            Map.class
        );

        assertThat(retryRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retryRes.getBody().get("status")).isEqualTo("SUCCESS");

        // Verify funds moved on successful retry
        assertThat(accountRepository.findById(accFrom.id()).orElseThrow().balance()).isEqualTo(balanceFromBefore - 10_000L);
        assertThat(accountRepository.findById(accTo.id()).orElseThrow().balance()).isEqualTo(balanceToBefore + 10_000L);
    }
}
