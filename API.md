# LedgerLine API Reference Documentation

All endpoints (except `/api/auth/login` and `/actuator/health`) require an `Authorization: Bearer <JWT>` header.  
All amounts represent **paise** (1 INR = 100 paise) as positive 64-bit integers.

---

## Endpoint Summary Table

| HTTP Method | Path | Allowed Roles | Ownership / Membership Rule | Effect on Money | Idempotency Required? | Possible HTTP Statuses |
|---|---|---|---|---|:---:|---|
| `POST` | `/api/auth/login` | *Public (PermitAll)* | N/A (validates username and password against BCrypt hash) | None | No | `200 OK`, `400 Bad Request`, `401 Unauthorized`, `429 Too Many Requests` |
| `GET` | `/actuator/health` | *Public (PermitAll)* | N/A (Spring Boot health liveness & readiness check) | None | No | `200 OK`, `503 Service Unavailable` |
| `GET` | `/api/accounts` | `HOLDER`, `ADMIN`, `AUDITOR` | `HOLDER`: filtered strictly to accounts where `account_members.user_id = auth_user_id`. `ADMIN`/`AUDITOR`: returns all accounts. | None | No | `200 OK`, `401 Unauthorized` |
| `GET` | `/api/accounts/{id}` | `HOLDER`, `ADMIN`, `AUDITOR` | `HOLDER`: Must be an active member of `{id}`. `ADMIN`/`AUDITOR`: Any account. Non-members receive `404 Not Found`. | None | No | `200 OK`, `401 Unauthorized`, `404 Not Found` |
| `GET` | `/api/accounts/{id}/statement` | `HOLDER`, `ADMIN`, `AUDITOR` | `HOLDER`: Must be an active member of `{id}`. `ADMIN`/`AUDITOR`: Any account. Returns postings and recorded refusals. Non-members receive `404 Not Found`. | None | No | `200 OK`, `400 Bad Request`, `401 Unauthorized`, `404 Not Found` |
| `POST` | `/api/accounts/{id}/deposit` | `HOLDER`, `ADMIN` | Account `{id}` must exist. Member check verified. Credits account `{id}`, debits system counterparty `EXTERNAL`. | **Increases user balance** (Credits account `{id}` by `+amount`) | **YES** (`Idempotency-Key` header) | `200 OK` (SUCCESS), `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity`, `429 Too Many Requests` |
| `POST` | `/api/accounts/{id}/withdraw` | `HOLDER`, `ADMIN` | Caller must be an active member of account `{id}`. Debits account `{id}`, credits system counterparty `EXTERNAL`. | **Decreases user balance** (Debits account `{id}` by `-amount`) | **YES** (`Idempotency-Key` header) | `200 OK` (SUCCESS), `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity` (Refused / Insufficient Funds), `429 Too Many Requests` |
| `POST` | `/api/transfers` | `HOLDER`, `ADMIN` | Caller must be an active member of `fromAccountId`. Transfer to any valid account permitted. Debits source, credits destination. | **Conserves money** (Debits `fromAccountId` by `-amount`, credits `toAccountId` by `+amount`) | **YES** (`Idempotency-Key` header) | `200 OK` (SUCCESS), `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity` (Refused / Insufficient Funds), `429 Too Many Requests` |
| `POST` | `/api/admin/accounts` | `ADMIN` only | Admin privilege. Creates a new `USER` account with currency `INR`. Initial balance is 0 paise. | None | No | `200 OK`, `400 Bad Request`, `401 Unauthorized`, `403 Forbidden` |
| `POST` | `/api/admin/accounts/{id}/members` | `ADMIN` only | Admin privilege. Grants a user ID membership to account `{id}` in `account_members`. | None | No | `200 OK`, `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found` |
| `POST` | `/api/admin/accounts/{id}/adjust` | `ADMIN` only | Admin privilege. Audited double-entry `ADJUSTMENT` between `EXTERNAL` and account `{id}` with mandatory reason. | **Mutates balance** (respects non-negative constraint) | **YES** (`Idempotency-Key` header) | `200 OK` (SUCCESS), `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity` |
| `POST` | `/api/admin/entries/{id}/reverse` | `ADMIN` only | Admin privilege. Reverses entry `{id}` once (enforced by DB partial unique index) with mandatory reason. | **Reverses original postings** (respects non-negative constraint) | **YES** (`Idempotency-Key` header) | `200 OK` (SUCCESS), `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `422 Unprocessable Entity` |
| `GET` | `/api/admin/verify` | `ADMIN`, `AUDITOR` | Admin or Auditor privilege. Executes 7 forensic integrity checks in a read-only `REPEATABLE READ` snapshot. | None | No | `200 OK` (Report status `PASSED` or `FAILED`), `401 Unauthorized`, `403 Forbidden` |

---

## Detailed Payload Schemas

### 1. Authentication (`POST /api/auth/login`)
**Request**:
```json
{
  "username": "holder",
  "password": "HolderPassword_2026!"
}
```
**Response (200 OK)**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": 2,
    "username": "holder",
    "role": "HOLDER"
  }
}
```

### 2. Money Movements (`deposit`, `withdraw`, `transfer`)
**Deposit/Withdraw Request**:
```json
{
  "amount": 5000,
  "reason": "Funding invoice #1024"
}
```
**Transfer Request**:
```json
{
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 5000,
  "reason": "Payment transfer"
}
```
**Success Response (200 OK)**:
```json
{
  "status": "SUCCESS",
  "entryId": 101,
  "kind": "TRANSFER",
  "postings": [
    {
      "id": 201,
      "entryId": 101,
      "accountId": 1,
      "amount": -5000,
      "balanceAfter": 45000
    },
    {
      "id": 202,
      "entryId": 101,
      "accountId": 2,
      "amount": 5000,
      "balanceAfter": 15000
    }
  ]
}
```
**Refusal Response (422 Unprocessable Entity)**:
```json
{
  "status": "REFUSED",
  "refusalCode": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds: account balance 4000 paise is less than required 6000 paise",
  "attemptId": 45
}
```

### 3. Forensic System Verification (`GET /api/admin/verify`)
**Response (200 OK)**:
```json
{
  "timestamp": "2026-11-08T18:00:00Z",
  "status": "PASSED",
  "totalChecks": 7,
  "passedChecks": 7,
  "failedChecks": 0,
  "checks": {
    "allPostingsSumZero": { "status": "PASS", "detail": "Total net postings balance: 0 paise across all rows" },
    "cachedBalancesMatchPostings": { "status": "PASS", "detail": "All 8 account cached balances exactly match the sum of their postings" },
    "netExternalFlowMatchesUserBalances": { "status": "PASS", "detail": "Sum of user account balances (150000 paise) exactly matches net external flow" },
    "noNegativeUserBalances": { "status": "PASS", "detail": "No user accounts have a negative balance" },
    "hashChainsIntact": { "status": "PASS", "detail": "Both ledger_entries and audit_log hash chains verified intact" },
    "everyEntryPostingsBalanced": { "status": "PASS", "detail": "Every individual ledger entry's postings sum to 0" },
    "idempotencyDuplicateReplaysTracked": { "status": "PASS", "detail": "Total duplicate idempotency replays tracked: 42" }
  }
}
```
