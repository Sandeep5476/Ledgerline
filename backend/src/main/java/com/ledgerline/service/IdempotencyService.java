package com.ledgerline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.IdempotencyPayloadMismatchException;
import com.ledgerline.exception.IdempotencyValidationException;
import com.ledgerline.model.IdempotencyRecord;
import com.ledgerline.repository.IdempotencyRepository;
import com.ledgerline.util.CanonicalRequestHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    public void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyValidationException("Idempotency-Key header is required and cannot be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IdempotencyValidationException("Idempotency-Key header exceeds maximum allowed length of " + MAX_KEY_LENGTH + " characters");
        }
        if (key.contains(" ") || !key.equals(key.trim())) {
            throw new IdempotencyValidationException("Idempotency-Key header cannot contain whitespace");
        }
    }

    /**
     * Executes an operation with strict idempotency semantics inside a single database transaction.
     *
     * EXPLANATION OF RACE WINDOW IMMUNITY AND ROLLBACK BEHAVIOR:
     * 1. Transaction T1 issues:
     *    INSERT INTO idempotency_keys (user_id, key, request_hash, status, created_at)
     *    VALUES (?, ?, ?, 'IN_PROGRESS', NOW()) ON CONFLICT (user_id, key) DO NOTHING;
     * 2. If T1 is the first to arrive, 1 row is inserted. T1 owns the key, moves money, updates
     *    status to 'COMPLETED' with the response payload, and commits.
     * 3. If concurrent transaction T2 attempts the same (user_id, key), PostgreSQL's unique index
     *    conflict detection forces T2's INSERT to WAIT on the row lock held by T1's transaction.
     *    There is ZERO race window in which both T1 and T2 can proceed simultaneously.
     * 4. When T1 finishes:
     *    a. If T1 COMMITS: T2 unblocks, detects the committed conflict, and inserts 0 rows (DO NOTHING).
     *       T2 then queries the stored row FOR UPDATE, sees status 'COMPLETED', validates request_hash,
     *       increments duplicate_count, and replays the cached response.
     *    b. If T1 ROLLS BACK (e.g. database disconnect or fatal failure): T1's inserted row vanishes!
     *       T2 unblocks, finds no conflict, and T2's INSERT SUCCEEDS (1 row inserted). T2 now cleanly
     *       assumes ownership of the key and executes the money move.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResponseEntity<?> execute(
        String rawKey,
        Long userId,
        String method,
        String path,
        Object requestBody,
        Supplier<ResponseEntity<?>> operation
    ) {
        validateKey(rawKey);

        String requestHash = CanonicalRequestHasher.computeRequestHash(method, path, userId, requestBody);

        boolean acquired = idempotencyRepository.tryAcquireKey(userId, rawKey, requestHash);

        if (acquired) {
            ResponseEntity<?> response = operation.get();

            try {
                String responseBodyJson = objectMapper.writeValueAsString(response.getBody());
                idempotencyRepository.markCompleted(userId, rawKey, response.getStatusCode().value(), responseBodyJson);
            } catch (Exception e) {
                log.error("Failed to serialize response body for idempotency key storage: {}", rawKey, e);
                throw new IllegalStateException("Failed to serialize response body for idempotency key", e);
            }

            return response;
        }

        Optional<IdempotencyRecord> existingOpt = idempotencyRepository.findForUpdate(userId, rawKey);

        if (existingOpt.isEmpty()) {
            boolean retryAcquired = idempotencyRepository.tryAcquireKey(userId, rawKey, requestHash);
            if (retryAcquired) {
                ResponseEntity<?> response = operation.get();
                try {
                    String responseBodyJson = objectMapper.writeValueAsString(response.getBody());
                    idempotencyRepository.markCompleted(userId, rawKey, response.getStatusCode().value(), responseBodyJson);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to serialize response body for idempotency key", e);
                }
                return response;
            }
            throw new IdempotencyConflictException("Concurrent request with idempotency key '" + rawKey + "' is in progress");
        }

        IdempotencyRecord record = existingOpt.get();

        if ("IN_PROGRESS".equals(record.status())) {
            throw new IdempotencyConflictException("Concurrent request with idempotency key '" + rawKey + "' is currently in progress");
        }

        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyPayloadMismatchException(
                "Idempotency key '" + rawKey + "' was previously used with a different request payload"
            );
        }

        idempotencyRepository.incrementDuplicateCount(userId, rawKey);
        log.info("Replaying cached response for idempotency key {} (user={}, duplicateCount={})",
            rawKey, userId, record.duplicateCount() + 1);

        try {
            Object cachedBody = objectMapper.readValue(record.responseBody(), Object.class);
            return ResponseEntity.status(record.responseStatus()).body(cachedBody);
        } catch (Exception e) {
            log.error("Failed to deserialize cached response body for idempotency key: {}", rawKey, e);
            throw new IllegalStateException("Failed to deserialize cached response body for idempotency key", e);
        }
    }
}

