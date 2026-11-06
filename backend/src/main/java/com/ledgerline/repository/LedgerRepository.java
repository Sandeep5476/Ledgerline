package com.ledgerline.repository;

import com.ledgerline.model.EntryKind;
import com.ledgerline.model.LedgerAttempt;
import com.ledgerline.model.LedgerEntry;
import com.ledgerline.model.Posting;
import com.ledgerline.util.HashUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ledger entries, postings, and attempts with cryptographic hash chaining.
 *
 * Appends to ledger_entries are serialized via pg_advisory_xact_lock to ensure an un-forkable,
 * linear cryptographic hash chain.
 */
@Repository
public class LedgerRepository {

    public static final long LEDGER_LOCK_ID = 8847120194827102L;

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<LedgerEntry> ENTRY_ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
        rs.getLong("id"),
        EntryKind.valueOf(rs.getString("kind")),
        rs.getObject("reverses_entry_id") != null ? rs.getLong("reverses_entry_id") : null,
        rs.getLong("initiated_by"),
        rs.getString("idempotency_key"),
        rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("prev_hash"),
        rs.getString("hash")
    );

    private static final RowMapper<Posting> POSTING_ROW_MAPPER = (rs, rowNum) -> new Posting(
        rs.getLong("id"),
        rs.getLong("entry_id"),
        rs.getLong("account_id"),
        rs.getLong("amount"),
        rs.getLong("balance_after"),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<LedgerAttempt> ATTEMPT_ROW_MAPPER = (rs, rowNum) -> new LedgerAttempt(
        rs.getLong("id"),
        rs.getString("kind"),
        rs.getObject("from_account_id") != null ? rs.getLong("from_account_id") : null,
        rs.getObject("to_account_id") != null ? rs.getLong("to_account_id") : null,
        rs.getLong("amount"),
        rs.getLong("initiated_by"),
        rs.getString("idempotency_key"),
        rs.getString("status"),
        rs.getString("refusal_reason"),
        rs.getObject("balance_snapshot") != null ? rs.getLong("balance_snapshot") : null,
        rs.getTimestamp("created_at").toInstant()
    );

    public void acquireLedgerLock() {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + LEDGER_LOCK_ID + ")");
    }

    public String getLatestEntryHash() {
        String sql = "SELECT hash FROM ledger_entries ORDER BY id DESC LIMIT 1";
        try {
            String hash = jdbcTemplate.queryForObject(sql, String.class);
            return (hash != null && !hash.isBlank()) ? hash : HashUtils.GENESIS_HASH;
        } catch (EmptyResultDataAccessException e) {
            return HashUtils.GENESIS_HASH;
        }
    }

    public static String computeEntryHash(
        String prevHash,
        String kind,
        Long reversesEntryId,
        Long initiatedBy,
        String idempotencyKey,
        String reason,
        Instant createdAt
    ) {
        String canonical = (prevHash != null ? prevHash : HashUtils.GENESIS_HASH) + "|" +
                           (kind != null ? kind : "") + "|" +
                           (reversesEntryId != null ? reversesEntryId.toString() : "") + "|" +
                           (initiatedBy != null ? initiatedBy.toString() : "") + "|" +
                           (idempotencyKey != null ? idempotencyKey : "") + "|" +
                           (reason != null ? reason : "") + "|" +
                           (createdAt != null ? createdAt.toEpochMilli() : 0);
        return HashUtils.sha256Hex(canonical);
    }

    public LedgerEntry insertEntry(EntryKind kind, Long reversesEntryId, Long initiatedBy, String idempotencyKey, String reason) {
        // Serialise chain append to guarantee strictly linear chain with no forks
        acquireLedgerLock();

        String prevHash = getLatestEntryHash();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = computeEntryHash(prevHash, kind.name(), reversesEntryId, initiatedBy, idempotencyKey, reason, createdAt);

        String sql = "INSERT INTO ledger_entries (kind, reverses_entry_id, initiated_by, idempotency_key, reason, created_at, prev_hash, hash) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "RETURNING id, kind, reverses_entry_id, initiated_by, idempotency_key, reason, created_at, prev_hash, hash";
        return jdbcTemplate.queryForObject(
            sql,
            ENTRY_ROW_MAPPER,
            kind.name(),
            reversesEntryId,
            initiatedBy,
            idempotencyKey,
            reason,
            Timestamp.from(createdAt),
            prevHash,
            hash
        );
    }

    public List<LedgerEntry> findAllEntriesInOrder() {
        String sql = "SELECT id, kind, reverses_entry_id, initiated_by, idempotency_key, reason, created_at, prev_hash, hash " +
                     "FROM ledger_entries ORDER BY id ASC";
        return jdbcTemplate.query(sql, ENTRY_ROW_MAPPER);
    }

    public Posting insertPosting(Long entryId, Long accountId, long amount, long balanceAfter) {
        String sql = "INSERT INTO postings (entry_id, account_id, amount, balance_after, created_at) " +
                     "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                     "RETURNING id, entry_id, account_id, amount, balance_after, created_at";
        return jdbcTemplate.queryForObject(sql, POSTING_ROW_MAPPER, entryId, accountId, amount, balanceAfter);
    }

    public LedgerAttempt insertAttempt(
        String kind,
        Long fromAccountId,
        Long toAccountId,
        long amount,
        Long initiatedBy,
        String idempotencyKey,
        String status,
        String refusalReason,
        Long balanceSnapshot
    ) {
        String sql = "INSERT INTO ledger_attempts (kind, from_account_id, to_account_id, amount, initiated_by, idempotency_key, status, refusal_reason, balance_snapshot, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                     "RETURNING id, kind, from_account_id, to_account_id, amount, initiated_by, idempotency_key, status, refusal_reason, balance_snapshot, created_at";
        return jdbcTemplate.queryForObject(sql, ATTEMPT_ROW_MAPPER, kind, fromAccountId, toAccountId, amount, initiatedBy, idempotencyKey, status, refusalReason, balanceSnapshot);
    }

    public Optional<LedgerEntry> findEntryById(Long id) {
        String sql = "SELECT id, kind, reverses_entry_id, initiated_by, idempotency_key, reason, created_at, prev_hash, hash " +
                     "FROM ledger_entries WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ENTRY_ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Posting> findPostingsByEntryId(Long entryId) {
        String sql = "SELECT id, entry_id, account_id, amount, balance_after, created_at FROM postings WHERE entry_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(sql, POSTING_ROW_MAPPER, entryId);
    }

    public boolean isEntryReversed(Long entryId) {
        String sql = "SELECT COUNT(*) FROM ledger_entries WHERE reverses_entry_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, entryId);
        return count != null && count > 0;
    }
}

