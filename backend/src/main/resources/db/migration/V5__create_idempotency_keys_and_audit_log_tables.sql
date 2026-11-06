-- V5: Create idempotency_keys and audit_log tables
CREATE TABLE idempotency_keys (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status INT NULL,
    response_body JSONB NULL,
    duplicate_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, key)
);

CREATE TABLE audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NULL,
    details JSONB NULL,
    outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    prev_hash VARCHAR(64) NULL,
    hash VARCHAR(64) NULL
);

CREATE INDEX idx_audit_log_actor ON audit_log(actor_user_id);
CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);

