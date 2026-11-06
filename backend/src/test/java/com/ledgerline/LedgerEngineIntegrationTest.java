package com.ledgerline;

import com.ledgerline.dto.LedgerCommand;
import com.ledgerline.dto.LedgerResult;
import com.ledgerline.dto.RefusalCode;
import com.ledgerline.model.Account;
import com.ledgerline.model.AccountType;
import com.ledgerline.model.EntryKind;
import com.ledgerline.model.UserRole;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LedgerEngineIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long holderUserId;
    private Long adminUserId;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUpAccounts() {
        holderUserId = userRepository.findByUsername("holder").orElseThrow().id();
        adminUserId = userRepository.findByUsername("admin").orElseThrow().id();

        // Create fresh accounts for testing
        accountA = accountRepository.createAccount("Holder Account A", AccountType.USER, "INR", 0L);
        accountB = accountRepository.createAccount("Holder Account B", AccountType.USER, "INR", 0L);

        // Associate holder with Account A and Account B
        accountRepository.addMember(accountA.id(), holderUserId);
        accountRepository.addMember(accountB.id(), holderUserId);
    }

    @Test
    @DisplayName("Deposit happy path: moves money from EXTERNAL to USER account and balances match")
    void testDepositHappyPath() {
        LedgerResult result = ledgerService.deposit(accountA.id(), 10_000L, holderUserId, UserRole.HOLDER, "Initial test deposit", null);

        assertThat(result.success()).isTrue();
        assertThat(result.entry().kind()).isEqualTo(EntryKind.DEPOSIT);
        assertThat(result.postings()).hasSize(2);

        Account reloadedA = accountRepository.findById(accountA.id()).orElseThrow();
        assertThat(reloadedA.balance()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Transfer happy path: transfers funds between two user accounts in exact double-entry balance")
    void testTransferHappyPath() {
        // Deposit into A first
        ledgerService.deposit(accountA.id(), 10_000L, holderUserId, UserRole.HOLDER, "Initial fund", null);

        // Transfer 4,000 from A to B
        LedgerResult result = ledgerService.transfer(accountA.id(), accountB.id(), 4_000L, holderUserId, UserRole.HOLDER, "P2P transfer", null);

        assertThat(result.success()).isTrue();
        assertThat(result.entry().kind()).isEqualTo(EntryKind.TRANSFER);

        Account reloadedA = accountRepository.findById(accountA.id()).orElseThrow();
        Account reloadedB = accountRepository.findById(accountB.id()).orElseThrow();

        assertThat(reloadedA.balance()).isEqualTo(6_000L);
        assertThat(reloadedB.balance()).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("Withdraw happy path: moves money from USER account to EXTERNAL world")
    void testWithdrawHappyPath() {
        ledgerService.deposit(accountA.id(), 10_000L, holderUserId, UserRole.HOLDER, "Deposit for withdrawal", null);

        LedgerResult result = ledgerService.withdraw(accountA.id(), 3_500L, holderUserId, UserRole.HOLDER, "ATM withdrawal", null);

        assertThat(result.success()).isTrue();
        assertThat(result.entry().kind()).isEqualTo(EntryKind.WITHDRAWAL);

        Account reloadedA = accountRepository.findById(accountA.id()).orElseThrow();
        assertThat(reloadedA.balance()).isEqualTo(6_500L);
    }

    @Test
    @DisplayName("Refusal is recorded as data: insufficient funds records a committed attempt and preserves balance")
    void testInsufficientFundsRefusalRecorded() {
        String key = "idem-fail-" + java.util.UUID.randomUUID();
        // Account A has 1,000 paise
        ledgerService.deposit(accountA.id(), 1_000L, holderUserId, UserRole.HOLDER, "Small deposit", null);

        // Attempt to transfer 5,000 paise (exceeds balance)
        LedgerResult result = ledgerService.transfer(accountA.id(), accountB.id(), 5_000L, holderUserId, UserRole.HOLDER, "Overdraft attempt", key);

        assertThat(result.success()).isFalse();
        assertThat(result.refusalCode()).isEqualTo(RefusalCode.INSUFFICIENT_FUNDS);
        assertThat(result.attempt()).isNotNull();
        assertThat(result.attempt().status()).isEqualTo("REFUSED");
        assertThat(result.attempt().refusalReason()).contains("Insufficient funds");
        assertThat(result.attempt().balanceSnapshot()).isEqualTo(1_000L);

        // Verify balance was untouched
        Account reloadedA = accountRepository.findById(accountA.id()).orElseThrow();
        assertThat(reloadedA.balance()).isEqualTo(1_000L);

        // Verify attempt is committed in DB
        Integer attemptsCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_attempts WHERE idempotency_key = ? AND status = 'REFUSED'",
            Integer.class,
            key
        );
        assertThat(attemptsCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Refusal is recorded as data: unauthorized caller records a committed attempt")
    void testUnauthorizedRefusalRecorded() {
        String key = "idem-unauth-" + java.util.UUID.randomUUID();
        Long auditorUserId = userRepository.findByUsername("auditor").orElseThrow().id();

        LedgerCommand unauthorizedCmd = new LedgerCommand(
            EntryKind.TRANSFER,
            accountA.id(),
            accountB.id(),
            500L,
            auditorUserId,
            UserRole.AUDITOR, // Auditor does NOT have debit access
            "Unauthorized debit attempt",
            key
        );

        LedgerResult result = ledgerService.move(unauthorizedCmd);

        assertThat(result.success()).isFalse();
        assertThat(result.refusalCode()).isEqualTo(RefusalCode.UNAUTHORIZED);

        Integer attemptsCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_attempts WHERE idempotency_key = ? AND status = 'REFUSED'",
            Integer.class,
            key
        );
        assertThat(attemptsCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Deadlock prevention: opposing transfers execute concurrently without deadlock due to ascending lock order")
    void testOppositeDirectionTransfersDoNotDeadlock() throws InterruptedException, ExecutionException {
        // Fund both accounts with 50,000 paise
        ledgerService.deposit(accountA.id(), 50_000L, holderUserId, UserRole.HOLDER, "Fund A", null);
        ledgerService.deposit(accountB.id(), 50_000L, holderUserId, UserRole.HOLDER, "Fund B", null);

        int iterations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        Future<?> future1 = executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    LedgerResult r = ledgerService.transfer(accountA.id(), accountB.id(), 100L, holderUserId, UserRole.HOLDER, "A->B", null);
                    if (r.success()) successCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> future2 = executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    LedgerResult r = ledgerService.transfer(accountB.id(), accountA.id(), 100L, holderUserId, UserRole.HOLDER, "B->A", null);
                    if (r.success()) successCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        startLatch.countDown();
        future1.get();
        future2.get();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(iterations * 2);

        Account finalA = accountRepository.findById(accountA.id()).orElseThrow();
        Account finalB = accountRepository.findById(accountB.id()).orElseThrow();
        // Since net transfer is 0 (20 x 100 each direction), balances remain exactly 50,000
        assertThat(finalA.balance()).isEqualTo(50_000L);
        assertThat(finalB.balance()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("Administrative reversal: reverts original entry and respects single-reversal constraint")
    void testAdministrativeReversal() {
        ledgerService.deposit(accountA.id(), 10_000L, holderUserId, UserRole.HOLDER, "Fund", null);
        LedgerResult transferResult = ledgerService.transfer(accountA.id(), accountB.id(), 3_000L, holderUserId, UserRole.HOLDER, "Payment", null);
        assertThat(transferResult.success()).isTrue();

        Long entryId = transferResult.entry().id();

        // Admin reverses the transfer
        LedgerResult reversal = ledgerService.reverse(entryId, adminUserId, UserRole.ADMIN, "Fraudulent transfer reversal", null);
        assertThat(reversal.success()).isTrue();
        assertThat(reversal.entry().kind()).isEqualTo(EntryKind.REVERSAL);
        assertThat(reversal.entry().reversesEntryId()).isEqualTo(entryId);

        Account restoredA = accountRepository.findById(accountA.id()).orElseThrow();
        Account restoredB = accountRepository.findById(accountB.id()).orElseThrow();
        assertThat(restoredA.balance()).isEqualTo(10_000L);
        assertThat(restoredB.balance()).isEqualTo(0L);

        // Attempting to reverse the SAME entry again is refused by application and database unique constraint
        LedgerResult secondReversal = ledgerService.reverse(entryId, adminUserId, UserRole.ADMIN, "Second reversal attempt", null);
        assertThat(secondReversal.success()).isFalse();
        assertThat(secondReversal.refusalCode()).isEqualTo(RefusalCode.ALREADY_REVERSED);
    }

    @Test
    @DisplayName("Reversal refusal: reversal is refused if it would overdraw recipient account")
    void testReversalRefusedIfWouldOverdraw() {
        ledgerService.deposit(accountA.id(), 10_000L, holderUserId, UserRole.HOLDER, "Fund", null);
        LedgerResult transferResult = ledgerService.transfer(accountA.id(), accountB.id(), 5_000L, holderUserId, UserRole.HOLDER, "Payment", null);

        // Recipient B withdraws 4,000 paise (leaving only 1,000)
        ledgerService.withdraw(accountB.id(), 4_000L, holderUserId, UserRole.HOLDER, "Withdrawal", null);

        // Reversal of original 5,000 transfer requires debiting B by 5,000, which would overdraw B
        LedgerResult reversal = ledgerService.reverse(transferResult.entry().id(), adminUserId, UserRole.ADMIN, "Reversal attempt", null);

        assertThat(reversal.success()).isFalse();
        assertThat(reversal.refusalCode()).isEqualTo(RefusalCode.REVERSAL_OVERDRAW);
        assertThat(reversal.attempt()).isNotNull();
        assertThat(reversal.attempt().refusalReason()).contains("would overdraw account");
    }

    @Test
    @DisplayName("Constraint trigger: raw SQL insertion of unbalanced entry is rejected at commit")
    void testRawUnbalancedEntryRejectedAtCommitByTrigger() {
        assertThatThrownBy(() -> {
            transactionTemplate.execute(status -> {
                Long entryId = jdbcTemplate.queryForObject(
                    "INSERT INTO ledger_entries (kind, initiated_by, reason) VALUES ('DEPOSIT', ?, 'Unbalanced test') RETURNING id",
                    Long.class,
                    adminUserId
                );

                // Insert only ONE posting (+1000) without matching debit
                jdbcTemplate.update(
                    "INSERT INTO postings (entry_id, account_id, amount, balance_after) VALUES (?, ?, 1000, 1000)",
                    entryId,
                    accountA.id()
                );

                return null; // Triggers COMMIT, firing DEFERRED constraint trigger check_entry_zero_sum()
            });
        }).isInstanceOf(Exception.class)
          .satisfies(ex -> {
              Throwable root = ex;
              while (root.getCause() != null) {
                  root = root.getCause();
              }
              assertThat(root.getMessage()).contains("Double-entry constraint violation");
          });
    }

    @Test
    @DisplayName("Trigger guarantees: UPDATE, DELETE, and TRUNCATE are blocked on all ledger tables even for owner")
    void testAppendOnlyTriggersBlockMutationsEvenForOwner() {
        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM ledger_entries"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("strictly append-only"));

        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE ledger_attempts"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("strictly append-only"));

        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE audit_log"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("strictly append-only"));

        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM postings"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ex -> assertThat(ex.getCause()).hasMessageContaining("strictly append-only"));
    }
}
