-- V2: Create accounts and account_members tables
-- Money is ALWAYS represented as BIGINT minor units (paise). Never float, double, or numeric.

CREATE TABLE accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('USER', 'EXTERNAL', 'SYSTEM')),
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_account_non_negative CHECK (type <> 'USER' OR balance >= 0)
);

CREATE TABLE account_members (
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, user_id)
);

CREATE INDEX idx_account_members_user ON account_members(user_id);

-- Seed initial external world account (counterparty for outside money movement)
INSERT INTO accounts (name, type, currency, balance, created_at)
VALUES ('External World', 'EXTERNAL', 'INR', 0, CURRENT_TIMESTAMP);

