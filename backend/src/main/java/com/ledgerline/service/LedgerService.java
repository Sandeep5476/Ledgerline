package com.ledgerline.service;

import com.ledgerline.dto.LedgerCommand;
import com.ledgerline.dto.LedgerResult;
import com.ledgerline.dto.RefusalCode;
import com.ledgerline.model.*;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    // Maximum cap per transaction: 1,000,000,000 INR (100,000,000,000 paise / 100 crore)
    public static final long MAX_TRANSACTION_CAP = 100_000_000_000L;

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;
    private final AuditService auditService;

    public LedgerService(
        AccountRepository accountRepository,
        LedgerRepository ledgerRepository,
        AuditService auditService
    ) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.auditService = auditService;
    }

    /**
     * Executes money movement in a single atomic transaction.
     *
     * CONCURRENCY & ISOLATION CHOICE:
     * Isolation level READ COMMITTED is strictly correct and optimal here because all involved
     * accounts are locked using pessimistic write locks (SELECT ... FOR UPDATE) in strict
     * numerical ASCENDING id order BEFORE reading any balance.
     * This guarantees:
     * 1. No other transaction can mutate or read uncommitted balance states of the locked accounts.
     * 2. No deadlocks occur across opposing concurrent transfers (e.g. A->B vs B->A).
     * 3. Zero serializable abort/retry storm overhead while guaranteeing strict serial consistency.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerResult move(LedgerCommand cmd) {
        // 1. Parse and validate command parameters
        if (cmd.amount() <= 0) {
            return recordRefusal(cmd, null, RefusalCode.INVALID_COMMAND, "Amount must be greater than zero");
        }

        if (cmd.amount() > MAX_TRANSACTION_CAP) {
            return recordRefusal(cmd, null, RefusalCode.INVALID_COMMAND, "Amount exceeds maximum transaction cap of " + MAX_TRANSACTION_CAP + " paise");
        }

        if (cmd.fromAccountId() == null || cmd.toAccountId() == null) {
            return recordRefusal(cmd, null, RefusalCode.INVALID_COMMAND, "Source and destination account IDs must be specified");
        }

        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            return recordRefusal(cmd, null, RefusalCode.INVALID_COMMAND, "Source and destination accounts must be distinct");
        }

        // 2. Lock ALL involved accounts with SELECT ... FOR UPDATE in ASCENDING ID order
        Map<Long, Account> lockedAccounts = accountRepository.lockAccountsInOrder(cmd.fromAccountId(), cmd.toAccountId());

        Account fromAccount = lockedAccounts.get(cmd.fromAccountId());
        Account toAccount = lockedAccounts.get(cmd.toAccountId());

        if (fromAccount == null || toAccount == null) {
            return recordRefusal(cmd, null, RefusalCode.ACCOUNT_NOT_FOUND, "One or more involved accounts do not exist");
        }

        // 3. Authorisation check based strictly on AUTHENTICATED caller principal
        if (!isAuthorized(cmd, fromAccount, toAccount)) {
            Long snapshot = fromAccount.type() == AccountType.USER ? fromAccount.balance() : toAccount.balance();
            return recordRefusal(cmd, snapshot, RefusalCode.UNAUTHORIZED, "Caller is not authorized to debit source account or initiate this movement");
        }

        // 4. Check sufficient funds from the locked row
        if (fromAccount.type() == AccountType.USER && fromAccount.balance() < cmd.amount()) {
            return recordRefusal(
                cmd,
                fromAccount.balance(),
                RefusalCode.INSUFFICIENT_FUNDS,
                "Insufficient funds: account balance " + fromAccount.balance() + " paise is less than required " + cmd.amount() + " paise"
            );
        }

        // 5. Overflow-safe balance calculations with Math.addExact and Math.subtractExact
        long newFromBalance = Math.subtractExact(fromAccount.balance(), cmd.amount());
        long newToBalance = Math.addExact(toAccount.balance(), cmd.amount());

        // 6. Record immutable journal entry
        LedgerEntry entry = ledgerRepository.insertEntry(
            cmd.kind(),
            null,
            cmd.callerId(),
            cmd.idempotencyKey(),
            cmd.reason()
        );

        // 7. Insert postings (signed: debit is negative, credit is positive; exactly balances to 0)
        Posting debitPosting = ledgerRepository.insertPosting(entry.id(), fromAccount.id(), -cmd.amount(), newFromBalance);
        Posting creditPosting = ledgerRepository.insertPosting(entry.id(), toAccount.id(), cmd.amount(), newToBalance);

        // 8. Update cached balances on accounts
        accountRepository.updateBalance(fromAccount.id(), newFromBalance);
        accountRepository.updateBalance(toAccount.id(), newToBalance);

        // 9. Record audit log entry in the same transaction
        String details = String.format("{\"kind\":\"%s\",\"from\":%d,\"to\":%d,\"amount\":%d}",
            cmd.kind(), fromAccount.id(), toAccount.id(), cmd.amount());
        auditService.logMoneyMove(cmd.callerId(), entry.id(), details);

        log.info("Ledger entry {} successfully committed: {} paise moved from account {} to account {}",
            entry.id(), cmd.amount(), fromAccount.id(), toAccount.id());

        return LedgerResult.success(entry, List.of(debitPosting, creditPosting));
    }

    /**
     * Executes an administrative reversal of an existing entry.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerResult reverse(Long entryId, Long adminUserId, UserRole adminRole, String reason, String idempotencyKey) {
        if (adminRole != UserRole.ADMIN) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.UNAUTHORIZED, "Reversal is strictly restricted to ADMIN role");
        }

        if (reason == null || reason.isBlank()) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.INVALID_COMMAND, "Reversal reason is mandatory");
        }

        Optional<LedgerEntry> originalEntryOpt = ledgerRepository.findEntryById(entryId);
        if (originalEntryOpt.isEmpty()) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.INVALID_COMMAND, "Original ledger entry " + entryId + " not found");
        }

        LedgerEntry originalEntry = originalEntryOpt.get();

        if (originalEntry.kind() == EntryKind.REVERSAL) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.INVALID_COMMAND, "Cannot reverse an entry that is already a reversal");
        }

        if (ledgerRepository.isEntryReversed(originalEntry.id())) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.ALREADY_REVERSED, "Entry " + entryId + " has already been reversed");
        }

        List<Posting> originalPostings = ledgerRepository.findPostingsByEntryId(originalEntry.id());
        if (originalPostings.size() != 2) {
            LedgerCommand dummy = new LedgerCommand(EntryKind.REVERSAL, null, null, 1L, adminUserId, adminRole, reason, idempotencyKey);
            return recordRefusal(dummy, null, RefusalCode.INVALID_COMMAND, "Original entry postings structure is invalid for reversal");
        }

        Posting originalDebit = originalPostings.stream().filter(p -> p.amount() < 0).findFirst().orElseThrow();
        Posting originalCredit = originalPostings.stream().filter(p -> p.amount() > 0).findFirst().orElseThrow();

        long amount = originalCredit.amount();
        // In reverse: original recipient (originalCredit.accountId()) is DEBITED, original sender is CREDITED
        Long debitAccountId = originalCredit.accountId();
        Long creditAccountId = originalDebit.accountId();

        LedgerCommand reversalCmd = new LedgerCommand(
            EntryKind.REVERSAL,
            debitAccountId,
            creditAccountId,
            amount,
            adminUserId,
            adminRole,
            reason,
            idempotencyKey
        );

        // Lock accounts in ascending numerical ID order
        Map<Long, Account> locked = accountRepository.lockAccountsInOrder(debitAccountId, creditAccountId);
        Account debitAccount = locked.get(debitAccountId);
        Account creditAccount = locked.get(creditAccountId);

        if (debitAccount == null || creditAccount == null) {
            return recordRefusal(reversalCmd, null, RefusalCode.ACCOUNT_NOT_FOUND, "Involved accounts for reversal do not exist");
        }

        // Check non-negative rule on the account being debited in reversal
        if (debitAccount.type() == AccountType.USER && debitAccount.balance() < amount) {
            return recordRefusal(
                reversalCmd,
                debitAccount.balance(),
                RefusalCode.REVERSAL_OVERDRAW,
                "Reversal refused: would overdraw account " + debitAccount.id() + " (balance: " + debitAccount.balance() + " paise, required: " + amount + " paise)"
            );
        }

        long newDebitBalance = Math.subtractExact(debitAccount.balance(), amount);
        long newCreditBalance = Math.addExact(creditAccount.balance(), amount);

        LedgerEntry reversalEntry = ledgerRepository.insertEntry(
            EntryKind.REVERSAL,
            originalEntry.id(),
            adminUserId,
            idempotencyKey,
            reason
        );

        Posting revDebitPosting = ledgerRepository.insertPosting(reversalEntry.id(), debitAccount.id(), -amount, newDebitBalance);
        Posting revCreditPosting = ledgerRepository.insertPosting(reversalEntry.id(), creditAccount.id(), amount, newCreditBalance);

        accountRepository.updateBalance(debitAccount.id(), newDebitBalance);
        accountRepository.updateBalance(creditAccount.id(), newCreditBalance);

        String revDetails = String.format("{\"reversalEntryId\":%d,\"originalEntryId\":%d,\"amount\":%d}",
            reversalEntry.id(), originalEntry.id(), amount);
        auditService.logReversal(adminUserId, reversalEntry.id(), revDetails);

        log.info("Successfully executed reversal entry {} for original entry {}", reversalEntry.id(), originalEntry.id());

        return LedgerResult.success(reversalEntry, List.of(revDebitPosting, revCreditPosting));
    }

    public LedgerResult deposit(Long accountId, long amount, Long callerId, UserRole callerRole, String reason, String idempotencyKey) {
        Account external = accountRepository.findExternalWorldAccount()
            .orElseThrow(() -> new IllegalStateException("FATAL: External counterparty account not found in database"));

        LedgerCommand cmd = new LedgerCommand(
            EntryKind.DEPOSIT,
            external.id(),
            accountId,
            amount,
            callerId,
            callerRole,
            reason,
            idempotencyKey
        );
        return move(cmd);
    }

    public LedgerResult withdraw(Long accountId, long amount, Long callerId, UserRole callerRole, String reason, String idempotencyKey) {
        Account external = accountRepository.findExternalWorldAccount()
            .orElseThrow(() -> new IllegalStateException("FATAL: External counterparty account not found in database"));

        LedgerCommand cmd = new LedgerCommand(
            EntryKind.WITHDRAWAL,
            accountId,
            external.id(),
            amount,
            callerId,
            callerRole,
            reason,
            idempotencyKey
        );
        return move(cmd);
    }

    public LedgerResult transfer(Long fromAccountId, Long toAccountId, long amount, Long callerId, UserRole callerRole, String reason, String idempotencyKey) {
        LedgerCommand cmd = new LedgerCommand(
            EntryKind.TRANSFER,
            fromAccountId,
            toAccountId,
            amount,
            callerId,
            callerRole,
            reason,
            idempotencyKey
        );
        return move(cmd);
    }

    private boolean isAuthorized(LedgerCommand cmd, Account fromAccount, Account toAccount) {
        if (cmd.callerRole() == UserRole.ADMIN) {
            return true;
        }

        if (cmd.kind() == EntryKind.DEPOSIT) {
            // Deposits: caller must be member of target account
            return accountRepository.isUserMemberOfAccount(toAccount.id(), cmd.callerId());
        }

        // Withdrawals and Transfers: caller must be member of the debit account
        return accountRepository.isUserMemberOfAccount(fromAccount.id(), cmd.callerId());
    }

    /**
     * Records a refused attempt into ledger_attempts so it COMMITS as data.
     */
    private LedgerResult recordRefusal(LedgerCommand cmd, Long balanceSnapshot, RefusalCode code, String reason) {
        log.warn("Refusing ledger {} movement: {} (caller={}, from={}, to={}, amount={})",
            cmd.kind(), reason, cmd.callerId(), cmd.fromAccountId(), cmd.toAccountId(), cmd.amount());

        LedgerAttempt attempt = ledgerRepository.insertAttempt(
            cmd.kind() != null ? cmd.kind().name() : "UNKNOWN",
            cmd.fromAccountId(),
            cmd.toAccountId(),
            cmd.amount() > 0 ? cmd.amount() : 1L, // DB check constraint requires amount > 0
            cmd.callerId(),
            cmd.idempotencyKey(),
            "REFUSED",
            reason,
            balanceSnapshot
        );

        String refusalDetails = String.format("{\"attemptId\":%d,\"code\":\"%s\",\"reason\":\"%s\"}",
            attempt.id(), code != null ? code.name() : "UNKNOWN", reason != null ? reason.replace("\"", "\\\"") : "");
        auditService.logRefusal(cmd.callerId(), attempt.id(), refusalDetails);

        return LedgerResult.refused(attempt, code, reason);
    }

    /**
     * Cryptographically verifies the integrity of the ledger_entries hash chain.
     * Throws SecurityException if any link is broken, forged, or tampered with.
     */
    @Transactional(readOnly = true)
    public void verifyLedgerEntriesChain() {
        List<LedgerEntry> entries = ledgerRepository.findAllEntriesInOrder();
        if (entries.isEmpty()) {
            return;
        }

        String expectedPrevHash = com.ledgerline.util.HashUtils.GENESIS_HASH;

        for (LedgerEntry entry : entries) {
            if (!entry.prevHash().equals(expectedPrevHash)) {
                throw new SecurityException(String.format(
                    "Ledger chain integrity violation at entry ID %d: expected prev_hash %s but was %s",
                    entry.id(), expectedPrevHash, entry.prevHash()
                ));
            }

            String recomputedHash = LedgerRepository.computeEntryHash(
                entry.prevHash(),
                entry.kind().name(),
                entry.reversesEntryId(),
                entry.initiatedBy(),
                entry.idempotencyKey(),
                entry.reason(),
                entry.createdAt()
            );

            if (!entry.hash().equals(recomputedHash)) {
                throw new SecurityException(String.format(
                    "Ledger payload tampering violation at entry ID %d: recomputed hash %s does not match recorded hash %s",
                    entry.id(), recomputedHash, entry.hash()
                ));
            }

            expectedPrevHash = entry.hash();
        }

        log.info("Ledger entries cryptographic chain verified successfully across {} entries.", entries.size());
    }
}
