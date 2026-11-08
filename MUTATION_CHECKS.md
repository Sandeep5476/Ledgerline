# LedgerLine Mutation Testing & Proof of Defense Matrix

This document provides the exact one-line code/database mutations that dismantle each core security defense, explains why that defense is critical, and identifies the exact test cases in the test suite that fail when that protection is removed.

> [!WARNING]
> These mutations are documented for proof of test suite rigor and vulnerability analysis. They must **never** be committed to the `main` branch.

---

## 1. Mutation Summary Table

| Defense Removed | Source / Location | Exact One-Line Mutation | Tests That Fail | Vulnerability Exposed |
|---|---|---|---|---|
| **(a) Remove `FOR UPDATE`** | `AccountRepository.java` | Replace `... ORDER BY id ASC FOR UPDATE` with `... ORDER BY id ASC` | `Task5ConcurrencyAndIntegrityIntegrationTest#test1_ConcurrentOverdraw`, `test2_ConcurrentTransferOverdraw`, `LedgerEngineIntegrationTest#testInsufficientFundsRefusalRecorded` | **Read-then-write race condition**: multiple threads read the same balance concurrently, allowing overdraft beyond initial funds (negative balance double-spend). |
| **(b) Lock in Non-Ascending Order** | `AccountRepository.java` | Replace `... ORDER BY id ASC FOR UPDATE` with `... ORDER BY id DESC FOR UPDATE` (or locking `from` then `to`) | `Task5ConcurrencyAndIntegrityIntegrationTest#test3_OppositeDirectionTransfersNoDeadlock` | **Cyclic Deadlock**: concurrent transfers between accounts A and B in opposite directions (A->B and B->A) deadlock with `PSQLException: deadlock detected` or future timeout. |
| **(c) Remove `CHECK (balance >= 0)`** | `V1__initial_schema.sql` | Remove `CONSTRAINT chk_accounts_user_balance_non_negative CHECK (type <> 'USER' OR balance >= 0)` | `LedgerEngineIntegrationTest#testDbLevelCheckConstraintPreventsNegativeUserBalance`, `Task4SecurityAndAdminIntegrationTest#testVerifyEndpointPassesAndDetectsTampering` | **Loss of Defense-in-Depth**: unverified direct SQL or logic bugs can leave user accounts in negative states without database-level rejection. |
| **(d) Remove Idempotency Index or `ON CONFLICT`** | `V3__idempotency_and_audit.sql` / `IdempotencyService.java` | Remove `ON CONFLICT (user_id, key) DO NOTHING` in `IdempotencyService#execute` | `Task5ConcurrencyAndIntegrityIntegrationTest#test5_ConcurrentSameKeyReplay`, `IdempotencyAndAuditIntegrationTest#testTransferIdempotencyReplay` | **Check-then-insert race condition**: concurrent requests with the identical key both proceed, executing duplicate money movements and failing replay duplicate counts. |
| **(e) Drop Zero-Sum Constraint Trigger** | `V2__append_only_and_triggers.sql` | Execute `DROP TRIGGER trg_check_entry_zero_sum ON postings;` | `Task5ConcurrencyAndIntegrityIntegrationTest#test10_UnbalancedEntryRejectedAtCommit`, `LedgerEngineIntegrationTest#testRawUnbalancedEntryRejectedAtCommitByTrigger` | **Unbalanced Double-Entry Money Creation**: money can be created or destroyed out of thin air by inserting postings that do not sum to zero. |
| **(f) Drop Append-Only Triggers** | `V2__append_only_and_triggers.sql` | Execute `DROP TRIGGER trg_no_update_delete_ledger_entries ON ledger_entries;` | `Task5ConcurrencyAndIntegrityIntegrationTest#test9_AppendOnlyEnforcementAsAppRole`, `LedgerEngineIntegrationTest#testAppendOnlyTriggersBlockMutationsEvenForOwner` | **Ledger History Tampering**: entries or audit records can be updated or deleted directly in PostgreSQL, destroying forensic auditability. |
| **(g) Remove Membership Check** | `LedgerService.java` | In `LedgerService#isAuthorized`, replace `return isMember(cmd.fromAccountId(), cmd.callerId());` with `return true;` | `Task5ConcurrencyAndIntegrityIntegrationTest#test8_AuthorisationConstraints`, `Task4SecurityAndAdminIntegrationTest#testHolderCannotReadOrMoveOtherUserAccount` | **IDOR / Unauthorized Fund Movement**: any user can debit or withdraw from any other user's account simply by passing the target account ID. |

---

## 2. Detailed Mutation Recipes & Failure Signatures

### Mutation (a): Remove `FOR UPDATE`
- **File**: `backend/src/main/java/com/ledgerline/repository/AccountRepository.java`
- **Target Line**:
  ```java
  String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE";
  ```
- **Mutated Line**:
  ```java
  String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE id IN (?, ?) ORDER BY id ASC";
  ```
- **Observed Test Failure**:
  In `test1_ConcurrentOverdraw`, 20 concurrent threads each attempt to withdraw 60.00 from an account with balance 100.00. Without row locks, multiple threads observe `balance = 100.00` simultaneously and pass the funds check, resulting in `successCount > 1` (up to 20 successes), a negative final balance, and `/verify` failure.

---

### Mutation (b): Lock in Non-Ascending Order
- **File**: `backend/src/main/java/com/ledgerline/repository/AccountRepository.java`
- **Target Line**:
  ```java
  String sql = "SELECT id, name, type, currency, balance, created_at FROM accounts WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE";
  ```
- **Mutated Line**:
  ```java
  // Lock fromAccountId first, then toAccountId (arbitrary order based on parameter order)
  ```
- **Observed Test Failure**:
  In `test3_OppositeDirectionTransfersNoDeadlock`, 50 threads transfer A->B while 50 threads transfer B->A. Thread 1 locks A and waits for B; Thread 2 locks B and waits for A. The test immediately detects a deadlock:
  ```
  org.postgresql.util.PSQLException: ERROR: deadlock detected
  Detail: Process 12345 waits for ExclusiveLock on tuple ...
  ```

---

### Mutation (c): Remove `CHECK (balance >= 0)`
- **File**: `backend/src/main/resources/db/migration/V1__initial_schema.sql`
- **Target Line**:
  ```sql
  CONSTRAINT chk_accounts_user_balance_non_negative CHECK (type <> 'USER' OR balance >= 0)
  ```
- **Mutated Line**:
  ```sql
  -- CONSTRAINT chk_accounts_user_balance_non_negative dropped
  ```
- **Observed Test Failure**:
  `LedgerEngineIntegrationTest#testDbLevelCheckConstraintPreventsNegativeUserBalance` expects `PSQLException` on negative balance update; with constraint removed, the raw update succeeds and the test fails.

---

### Mutation (d): Remove Idempotency Unique Index or `ON CONFLICT`
- **File**: `backend/src/main/java/com/ledgerline/service/IdempotencyService.java`
- **Target Line**:
  ```java
  "INSERT INTO idempotency_keys (user_id, key, request_hash, status, created_at) VALUES (?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP) ON CONFLICT (user_id, key) DO NOTHING"
  ```
- **Mutated Line**:
  ```java
  "INSERT INTO idempotency_keys (user_id, key, request_hash, status, created_at) VALUES (?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)"
  ```
- **Observed Test Failure**:
  In `test5_ConcurrentSameKeyReplay`, 20 threads submitting the same key simultaneously race. Without `ON CONFLICT DO NOTHING`, multiple threads receive duplicate key constraint violations instead of waiting on the existing key lock, or if the unique index is removed, multiple duplicate entries are written to `ledger_entries`.

---

### Mutation (e): Drop Zero-Sum Constraint Trigger
- **File**: `backend/src/main/resources/db/migration/V2__append_only_and_triggers.sql`
- **Target Line**:
  ```sql
  CREATE CONSTRAINT TRIGGER trg_check_entry_zero_sum AFTER INSERT ON postings ...
  ```
- **Mutated Line**:
  ```sql
  -- Trigger dropped
  ```
- **Observed Test Failure**:
  `test10_UnbalancedEntryRejectedAtCommit` inserts an unbalanced entry (+500 paise with no matching debit) via raw JDBC and expects `conn.commit()` to fail with `Double-entry constraint violation: postings do not balance to zero`. With the trigger dropped, the transaction commits successfully and the test fails.

---

### Mutation (f): Drop Append-Only Triggers
- **File**: `backend/src/main/resources/db/migration/V2__append_only_and_triggers.sql`
- **Target Line**:
  ```sql
  CREATE TRIGGER trg_no_update_delete_ledger_entries BEFORE UPDATE OR DELETE ON ledger_entries ...
  ```
- **Mutated Line**:
  ```sql
  -- Trigger dropped
  ```
- **Observed Test Failure**:
  `LedgerEngineIntegrationTest#testAppendOnlyTriggersBlockMutationsEvenForOwner` attempts `DELETE FROM ledger_entries` as table owner and asserts that the trigger raises an exception. With the trigger dropped, rows are deleted and the test fails.

---

### Mutation (g): Remove Membership Check
- **File**: `backend/src/main/java/com/ledgerline/service/LedgerService.java`
- **Target Line**:
  ```java
  return accountRepository.isMember(fromAccount.id(), cmd.callerId());
  ```
- **Mutated Line**:
  ```java
  return true; // Bypass membership check
  ```
- **Observed Test Failure**:
  In `test8_AuthorisationConstraints`, Holder A attempts to withdraw from Holder B's account. With the check removed, the transaction succeeds instead of returning `404 Not Found` and recording a `REFUSED` attempt, causing the test assertions to fail.
