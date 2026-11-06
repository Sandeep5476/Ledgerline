package com.ledgerline.repository;

import com.ledgerline.model.AuditLogEntry;
import com.ledgerline.util.HashUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Repository for cryptographically hash-chained audit logging.
 *
 * THROUGHPUT TRADE-OFF EXPLANATION:
 * Appends to the audit log are serialized using PostgreSQL transaction-level advisory locks
 * (pg_advisory_xact_lock). This ensures that concurrent transactions appending to the chain
 * cannot execute concurrently across the reading of the latest 'prev_hash' and the insertion
 * of the new 'hash', preventing any cryptographic forks or branches.
 * The trade-off is that write operations that produce audit records are serialized sequentially,
 * establishing an upper bound on database write throughput proportional to 1 / (transaction latency).
 * For a high-security financial double-entry ledger, this trade-off is an intentional and necessary
 * design choice to guarantee complete, tamper-evident, non-repudiable audit verification.
 */
@Repository
public class AuditRepository {

    public static final long AUDIT_LOCK_ID = 8847120194827101L;

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AuditLogEntry> AUDIT_ROW_MAPPER = (rs, rowNum) -> new AuditLogEntry(
        rs.getLong("id"),
        rs.getObject("actor_user_id") != null ? rs.getLong("actor_user_id") : null,
        rs.getString("action"),
        rs.getString("target_type"),
        rs.getString("target_id"),
        rs.getString("details"),
        rs.getString("outcome"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("prev_hash"),
        rs.getString("hash")
    );

    public void acquireAuditLock() {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + AUDIT_LOCK_ID + ")");
    }

    public String getLatestHash() {
        String sql = "SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1";
        try {
            String hash = jdbcTemplate.queryForObject(sql, String.class);
            return (hash != null && !hash.isBlank()) ? hash : HashUtils.GENESIS_HASH;
        } catch (EmptyResultDataAccessException e) {
            return HashUtils.GENESIS_HASH;
        }
    }

    public AuditLogEntry record(
        Long actorUserId,
        String action,
        String targetType,
        String targetId,
        String detailsJson,
        String outcome
    ) {
        // Serialise chain append to guarantee strictly linear chain with no forks
        acquireAuditLock();

        String prevHash = getLatestHash();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = computeHash(prevHash, action, targetType, targetId, actorUserId, outcome, detailsJson, createdAt);

        String sql = "INSERT INTO audit_log (actor_user_id, action, target_type, target_id, details, outcome, created_at, prev_hash, hash) " +
                     "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) " +
                     "RETURNING id, actor_user_id, action, target_type, target_id, details::text, outcome, created_at, prev_hash, hash";

        return jdbcTemplate.queryForObject(
            sql,
            AUDIT_ROW_MAPPER,
            actorUserId,
            action,
            targetType,
            targetId,
            detailsJson != null ? detailsJson : "{}",
            outcome,
            Timestamp.from(createdAt),
            prevHash,
            hash
        );
    }

    public static String computeHash(
        String prevHash,
        String action,
        String targetType,
        String targetId,
        Long actorUserId,
        String outcome,
        String detailsJson,
        Instant createdAt
    ) {
        String canonicalDetails = com.ledgerline.util.CanonicalRequestHasher.canonicalizeJson(detailsJson);
        String canonical = (prevHash != null ? prevHash : HashUtils.GENESIS_HASH) + "|" +
                           (action != null ? action : "") + "|" +
                           (targetType != null ? targetType : "") + "|" +
                           (targetId != null ? targetId : "") + "|" +
                           (actorUserId != null ? actorUserId.toString() : "") + "|" +
                           (outcome != null ? outcome : "") + "|" +
                           canonicalDetails + "|" +
                           (createdAt != null ? createdAt.toEpochMilli() : 0);
        return HashUtils.sha256Hex(canonical);
    }

    public List<AuditLogEntry> findAllInOrder() {
        String sql = "SELECT id, actor_user_id, action, target_type, target_id, details::text, outcome, created_at, prev_hash, hash " +
                     "FROM audit_log ORDER BY id ASC";
        return jdbcTemplate.query(sql, AUDIT_ROW_MAPPER);
    }
}

