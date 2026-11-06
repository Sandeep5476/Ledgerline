package com.ledgerline.repository;

import com.ledgerline.model.IdempotencyRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<IdempotencyRecord> RECORD_ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
        rs.getLong("id"),
        rs.getLong("user_id"),
        rs.getString("key"),
        rs.getString("request_hash"),
        rs.getString("status"),
        rs.getObject("response_status") != null ? rs.getInt("response_status") : null,
        rs.getString("response_body"),
        rs.getInt("duplicate_count"),
        rs.getTimestamp("created_at").toInstant()
    );

    /**
     * Attempts atomic insertion of an in-progress idempotency key.
     * Returns true if this transaction acquired the key (1 row inserted).
     * Returns false if a conflict occurred (0 rows inserted, key already registered).
     */
    public boolean tryAcquireKey(Long userId, String key, String requestHash) {
        String sql = "INSERT INTO idempotency_keys (user_id, key, request_hash, status, created_at) " +
                     "VALUES (?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP) " +
                     "ON CONFLICT (user_id, key) DO NOTHING";
        int rows = jdbcTemplate.update(sql, userId, key, requestHash);
        return rows > 0;
    }

    /**
     * Marks the acquired key as completed with the response status code and JSON payload.
     */
    public void markCompleted(Long userId, String key, int responseStatus, String responseBodyJson) {
        String sql = "UPDATE idempotency_keys " +
                     "SET status = 'COMPLETED', response_status = ?, response_body = ?::jsonb " +
                     "WHERE user_id = ? AND key = ?";
        jdbcTemplate.update(sql, responseStatus, responseBodyJson != null ? responseBodyJson : "{}", userId, key);
    }

    /**
     * Locks and reads an existing idempotency record.
     */
    public Optional<IdempotencyRecord> findForUpdate(Long userId, String key) {
        String sql = "SELECT id, user_id, key, request_hash, status, response_status, response_body::text, duplicate_count, created_at " +
                     "FROM idempotency_keys " +
                     "WHERE user_id = ? AND key = ? " +
                     "FOR UPDATE";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, RECORD_ROW_MAPPER, userId, key));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Increments the replay counter for an existing key.
     */
    public void incrementDuplicateCount(Long userId, String key) {
        String sql = "UPDATE idempotency_keys SET duplicate_count = duplicate_count + 1 WHERE user_id = ? AND key = ?";
        jdbcTemplate.update(sql, userId, key);
    }
}

