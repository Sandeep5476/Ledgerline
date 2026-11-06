-- V3: Create ledger_entries and postings tables (Double-entry journal & postings)
-- NOTE: Append-only triggers and zero-sum transaction balance triggers are deferred to Task 2.

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'ADJUSTMENT', 'REVERSAL')),
    reverses_entry_id BIGINT NULL REFERENCES ledger_entries(id) ON DELETE RESTRICT,
    initiated_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NULL,
    reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    prev_hash VARCHAR(64) NULL,
    hash VARCHAR(64) NULL
);

CREATE INDEX idx_ledger_entries_initiated_by ON ledger_entries(initiated_by);
CREATE INDEX idx_ledger_entries_idempotency_key ON ledger_entries(idempotency_key);
CREATE INDEX idx_ledger_entries_reverses_entry_id ON ledger_entries(reverses_entry_id);

CREATE TABLE postings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id BIGINT NOT NULL REFERENCES ledger_entries(id) ON DELETE RESTRICT,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    amount BIGINT NOT NULL CHECK (amount <> 0), -- signed minor units: debit negative, credit positive
    balance_after BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_postings_entry_id ON postings(entry_id);
CREATE INDEX idx_postings_account_id ON postings(account_id);

