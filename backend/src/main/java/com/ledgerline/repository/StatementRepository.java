package com.ledgerline.repository;

import com.ledgerline.dto.StatementEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class StatementRepository {

    private final JdbcTemplate jdbcTemplate;

    public StatementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<StatementEntry> ENTRY_ROW_MAPPER = (rs, rowNum) -> new StatementEntry(
        rs.getString("type"),
        rs.getString("kind"),
        rs.getLong("amount"),
        rs.getObject("running_balance") != null ? rs.getLong("running_balance") : null,
        rs.getString("initiator_username"),
        rs.getString("idempotency_key"),
        rs.getObject("entry_id") != null ? rs.getLong("entry_id") : null,
        rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant()
    );

    public List<StatementEntry> findStatementEntries(Long accountId, Instant from, Instant to, int limit, int offset) {
        String sql = """
            SELECT 
                'POSTING' AS type,
                le.kind AS kind,
                p.amount AS amount,
                p.balance_after AS running_balance,
                u.username AS initiator_username,
                le.idempotency_key AS idempotency_key,
                p.entry_id AS entry_id,
                le.reason AS reason,
                p.created_at AS created_at
            FROM postings p
            JOIN ledger_entries le ON p.entry_id = le.id
            JOIN users u ON le.initiated_by = u.id
            WHERE p.account_id = ?
              AND (?::timestamptz IS NULL OR p.created_at >= ?::timestamptz)
              AND (?::timestamptz IS NULL OR p.created_at <= ?::timestamptz)

            UNION ALL

            SELECT 
                'REFUSED' AS type,
                la.kind AS kind,
                la.amount AS amount,
                la.balance_snapshot AS running_balance,
                u.username AS initiator_username,
                la.idempotency_key AS idempotency_key,
                NULL::bigint AS entry_id,
                la.refusal_reason AS reason,
                la.created_at AS created_at
            FROM ledger_attempts la
            JOIN users u ON la.initiated_by = u.id
            WHERE (la.from_account_id = ? OR la.to_account_id = ?)
              AND (?::timestamptz IS NULL OR la.created_at >= ?::timestamptz)
              AND (?::timestamptz IS NULL OR la.created_at <= ?::timestamptz)

            ORDER BY created_at ASC, entry_id ASC NULLS LAST
            LIMIT ? OFFSET ?
            """;

        Timestamp fromTs = from != null ? Timestamp.from(from) : null;
        Timestamp toTs = to != null ? Timestamp.from(to) : null;

        return jdbcTemplate.query(
            sql,
            ENTRY_ROW_MAPPER,
            accountId,
            fromTs, fromTs,
            toTs, toTs,
            accountId, accountId,
            fromTs, fromTs,
            toTs, toTs,
            limit,
            offset
        );
    }

    public long countStatementEntries(Long accountId, Instant from, Instant to) {
        String sql = """
            SELECT COUNT(*) FROM (
                SELECT p.id FROM postings p
                WHERE p.account_id = ?
                  AND (?::timestamptz IS NULL OR p.created_at >= ?::timestamptz)
                  AND (?::timestamptz IS NULL OR p.created_at <= ?::timestamptz)
                UNION ALL
                SELECT la.id FROM ledger_attempts la
                WHERE (la.from_account_id = ? OR la.to_account_id = ?)
                  AND (?::timestamptz IS NULL OR la.created_at >= ?::timestamptz)
                  AND (?::timestamptz IS NULL OR la.created_at <= ?::timestamptz)
            ) t
            """;

        Timestamp fromTs = from != null ? Timestamp.from(from) : null;
        Timestamp toTs = to != null ? Timestamp.from(to) : null;

        Long count = jdbcTemplate.queryForObject(
            sql,
            Long.class,
            accountId,
            fromTs, fromTs,
            toTs, toTs,
            accountId, accountId,
            fromTs, fromTs,
            toTs, toTs
        );
        return count != null ? count : 0L;
    }
}

