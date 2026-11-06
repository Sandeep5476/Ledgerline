-- V6: Create restricted application role (ledger_app) with least privilege
-- Migrations run as the owner (ledger_owner). The application connects as ledger_app.

DO $$
DECLARE
    app_pwd text := '${app_db_password}';
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledger_app') THEN
        BEGIN
            EXECUTE format('CREATE ROLE ledger_app WITH LOGIN PASSWORD %L', app_pwd);
        EXCEPTION
            WHEN insufficient_privilege THEN
                RAISE EXCEPTION 'Current database user lacks CREATEROLE privilege to create role ledger_app. In managed hosting environments (e.g. Neon, AWS RDS, Render), pre-create ledger_app and grant permissions manually as documented in README.md.';
        END;
    ELSE
        BEGIN
            EXECUTE format('ALTER ROLE ledger_app WITH LOGIN PASSWORD %L', app_pwd);
        EXCEPTION
            WHEN insufficient_privilege THEN
                -- If role exists but cannot be altered by current user, notice and proceed
                RAISE NOTICE 'Current database user cannot alter ledger_app role password. Ensure password is set manually.';
        END;
    END IF;
END
$$;

-- Grant schema usage
GRANT USAGE ON SCHEMA public TO ledger_app;

-- Explicitly revoke sensitive operations (defense in depth)
REVOKE UPDATE, DELETE, TRUNCATE ON ledger_entries, postings, ledger_attempts, audit_log FROM PUBLIC, ledger_app;
REVOKE DELETE, TRUNCATE ON accounts, idempotency_keys FROM PUBLIC, ledger_app;
REVOKE UPDATE, DELETE, TRUNCATE ON users, account_members FROM PUBLIC, ledger_app;

-- 1. Ledger and audit tables: append-only (SELECT, INSERT). NO UPDATE, NO DELETE, NO TRUNCATE.
GRANT SELECT, INSERT ON ledger_entries, postings, ledger_attempts, audit_log TO ledger_app;

-- 2. State & caching tables: SELECT, INSERT, UPDATE. NO DELETE, NO TRUNCATE.
GRANT SELECT, INSERT, UPDATE ON accounts, idempotency_keys TO ledger_app;

-- 3. Identity & membership tables: SELECT, INSERT. NO DELETE, NO TRUNCATE.
GRANT SELECT, INSERT ON users, account_members TO ledger_app;

-- 4. Sequences for identity generation
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ledger_app;
