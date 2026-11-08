package com.ledgerline.service;

import com.ledgerline.dto.SystemVerificationReport;
import com.ledgerline.dto.SystemVerificationReport.CheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemVerificationService {

    private final JdbcTemplate jdbcTemplate;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    public SystemVerificationService(
        JdbcTemplate jdbcTemplate,
        LedgerService ledgerService,
        AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SystemVerificationReport verifySystemIntegrity() {
        Map<String, CheckResult> checks = new LinkedHashMap<>();
        boolean allPassed = true;

        // a. SUM(postings.amount) over all postings = 0
        CheckResult checkA = verifyAllPostingsSumZero();
        checks.put("allPostingsSumZero", checkA);
        if ("FAIL".equals(checkA.status())) allPassed = false;

        // b. For every account, cached balance = SUM(its postings)
        CheckResult checkB = verifyCachedBalancesMatchPostings();
        checks.put("cachedBalancesMatchPostings", checkB);
        if ("FAIL".equals(checkB.status())) allPassed = false;

        // c. Sum of USER balances = net external flow (deposits - withdrawals, accounting for adjustments/reversals)
        CheckResult checkC = verifyNetExternalFlow();
        checks.put("netExternalFlowMatchesUserBalances", checkC);
        if ("FAIL".equals(checkC.status())) allPassed = false;

        // d. No USER balance < 0
        CheckResult checkD = verifyNoNegativeUserBalances();
        checks.put("noNegativeUserBalances", checkD);
        if ("FAIL".equals(checkD.status())) allPassed = false;

        // e. Hash chains intact (ledger_entries & audit_log)
        CheckResult checkE = verifyHashChains();
        checks.put("hashChainsIntact", checkE);
        if ("FAIL".equals(checkE.status())) allPassed = false;

        // f. Every individual entry's postings sum to 0
        CheckResult checkF = verifyEveryEntryPostingsBalanced();
        checks.put("everyEntryPostingsBalanced", checkF);
        if ("FAIL".equals(checkF.status())) allPassed = false;

        // g. Duplicate idempotency replays counted
        CheckResult checkG = countIdempotencyDuplicateReplays();
        checks.put("duplicateIdempotencyReplays", checkG);

        return new SystemVerificationReport(
            allPassed ? "PASSED" : "FAILED",
            Instant.now(),
            checks
        );
    }

    private CheckResult verifyAllPostingsSumZero() {
        Long sum = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM postings", Long.class);
        long netSum = sum != null ? sum : 0L;
        if (netSum == 0) {
            return CheckResult.pass("Sum of all postings across the system is exactly 0", Map.of("netSum", 0));
        }
        return CheckResult.fail("Double-entry zero-sum violation: net sum of all postings is " + netSum, Map.of("netSum", netSum));
    }

    private CheckResult verifyCachedBalancesMatchPostings() {
        String sql = """
            SELECT a.id, a.name, a.balance AS cached_balance, COALESCE(SUM(p.amount), 0) AS calculated_balance
            FROM accounts a
            LEFT JOIN postings p ON a.id = p.account_id
            GROUP BY a.id, a.name, a.balance
            HAVING a.balance <> COALESCE(SUM(p.amount), 0)
            """;
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(sql);
        if (mismatches.isEmpty()) {
            return CheckResult.pass("All account cached balances match the exact sum of their postings", Map.of("mismatchesCount", 0));
        }
        return CheckResult.fail("Detected " + mismatches.size() + " accounts with balance mismatches against postings", Map.of("mismatches", mismatches));
    }

    private CheckResult verifyNetExternalFlow() {
        String sql = """
            SELECT 
                COALESCE(SUM(CASE WHEN type = 'USER' THEN balance ELSE 0 END), 0) AS user_sum,
                COALESCE(SUM(CASE WHEN type = 'EXTERNAL' THEN balance ELSE 0 END), 0) AS external_sum
            FROM accounts
            """;
        Map<String, Object> row = jdbcTemplate.queryForMap(sql);
        long userSum = ((Number) row.get("user_sum")).longValue();
        long externalSum = ((Number) row.get("external_sum")).longValue();

        // In double-entry with an external counterparty: User Balances = - External Balance
        if (userSum + externalSum == 0) {
            return CheckResult.pass("Sum of USER balances (" + userSum + ") exactly equals net external flow (" + (-externalSum) + ")",
                Map.of("userBalanceSum", userSum, "netExternalFlow", -externalSum));
        }
        return CheckResult.fail("User balances (" + userSum + ") do not match net external flow (" + (-externalSum) + ")",
            Map.of("userBalanceSum", userSum, "externalBalanceSum", externalSum));
    }

    private CheckResult verifyNoNegativeUserBalances() {
        String sql = "SELECT id, name, balance FROM accounts WHERE type = 'USER' AND balance < 0";
        List<Map<String, Object>> negativeAccounts = jdbcTemplate.queryForList(sql);
        if (negativeAccounts.isEmpty()) {
            return CheckResult.pass("No USER account has a negative balance", Map.of("negativeAccountsCount", 0));
        }
        return CheckResult.fail("Detected " + negativeAccounts.size() + " USER accounts with negative balances", Map.of("violations", negativeAccounts));
    }

    private CheckResult verifyHashChains() {
        try {
            ledgerService.verifyLedgerEntriesChain();
            auditService.verifyAuditLogChain();
            return CheckResult.pass("Both ledger_entries and audit_log cryptographic hash chains are verified intact",
                Map.of("ledgerChain", "INTACT", "auditChain", "INTACT"));
        } catch (SecurityException e) {
            return CheckResult.fail("Cryptographic hash chain verification failed: " + e.getMessage(),
                Map.of("error", e.getMessage()));
        }
    }

    private CheckResult verifyEveryEntryPostingsBalanced() {
        String sql = """
            SELECT entry_id, SUM(amount) AS net_sum
            FROM postings
            GROUP BY entry_id
            HAVING SUM(amount) <> 0
            """;
        List<Map<String, Object>> unbalanced = jdbcTemplate.queryForList(sql);
        if (unbalanced.isEmpty()) {
            return CheckResult.pass("Every individual ledger entry's postings balance to zero", Map.of("unbalancedCount", 0));
        }
        return CheckResult.fail("Detected " + unbalanced.size() + " unbalanced ledger entries", Map.of("unbalanced", unbalanced));
    }

    private CheckResult countIdempotencyDuplicateReplays() {
        Long count = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(duplicate_count), 0) FROM idempotency_keys", Long.class);
        long totalReplays = count != null ? count : 0L;
        return CheckResult.pass("Duplicate idempotency replays successfully counted", Map.of("totalDuplicateReplays", totalReplays));
    }
}

