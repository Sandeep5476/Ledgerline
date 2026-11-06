-- V4: Create ledger_attempts table for failed/refused transaction audit trails
CREATE TABLE ledger_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    from_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    to_account_id BIGINT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    amount BIGINT NOT NULL CHECK (amount > 0),
    initiated_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('REFUSED')),
    refusal_reason TEXT NOT NULL,
    balance_snapshot BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_attempts_initiated_by ON ledger_attempts(initiated_by);
CREATE INDEX idx_ledger_attempts_idempotency_key ON ledger_attempts(idempotency_key);

