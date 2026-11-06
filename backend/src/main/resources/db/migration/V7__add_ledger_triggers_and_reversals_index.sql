-- V7: Append-only triggers, TRUNCATE prevention, zero-sum constraint trigger, and reversal uniqueness

-- 1. Common function to block mutations on append-only ledger tables
CREATE OR REPLACE FUNCTION prevent_ledger_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is strictly append-only. UPDATE, DELETE, and TRUNCATE operations are prohibited.', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- Row & statement triggers for ledger_entries
CREATE TRIGGER trg_no_update_delete_ledger_entries
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_delete_stmt_ledger_entries
    BEFORE DELETE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_truncate_ledger_entries
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

-- Row & statement triggers for postings
CREATE TRIGGER trg_no_update_delete_postings
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_delete_stmt_postings
    BEFORE DELETE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_truncate_postings
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

-- Row & statement triggers for ledger_attempts
CREATE TRIGGER trg_no_update_delete_ledger_attempts
    BEFORE UPDATE OR DELETE ON ledger_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_delete_stmt_ledger_attempts
    BEFORE DELETE ON ledger_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_truncate_ledger_attempts
    BEFORE TRUNCATE ON ledger_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

-- Row & statement triggers for audit_log
CREATE TRIGGER trg_no_update_delete_audit_log
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_delete_stmt_audit_log
    BEFORE DELETE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TRIGGER trg_no_truncate_audit_log
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

-- 2. Deferrable zero-sum balance constraint trigger on postings
CREATE OR REPLACE FUNCTION check_entry_zero_sum()
RETURNS trigger AS $$
DECLARE
    entry_sum BIGINT;
    target_entry_id BIGINT;
BEGIN
    target_entry_id := NEW.entry_id;

    SELECT COALESCE(SUM(amount), 0)
    INTO entry_sum
    FROM postings
    WHERE entry_id = target_entry_id;

    IF entry_sum <> 0 THEN
        RAISE EXCEPTION 'Double-entry constraint violation: postings for entry_id % do not balance to zero (net sum: %)', target_entry_id, entry_sum;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_postings_zero_sum
    AFTER INSERT OR UPDATE ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entry_zero_sum();

-- 3. Ensure an entry can be reversed at most once
CREATE UNIQUE INDEX idx_ledger_entries_reverses_unique
    ON ledger_entries(reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
