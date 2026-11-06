package com.ledgerline.service;

import com.ledgerline.model.AuditLogEntry;
import com.ledgerline.repository.AuditRepository;
import com.ledgerline.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLogEntry logMoneyMove(Long callerId, Long entryId, String detailsJson) {
        return auditRepository.record(callerId, "MONEY_MOVE", "LEDGER_ENTRY", String.valueOf(entryId), detailsJson, "SUCCESS");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLogEntry logRefusal(Long callerId, Long attemptId, String detailsJson) {
        return auditRepository.record(callerId, "LEDGER_REFUSAL", "LEDGER_ATTEMPT", String.valueOf(attemptId), detailsJson, "REFUSED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLogEntry logReversal(Long adminId, Long reversalEntryId, String detailsJson) {
        return auditRepository.record(adminId, "REVERSAL", "LEDGER_ENTRY", String.valueOf(reversalEntryId), detailsJson, "SUCCESS");
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLogEntry logLoginSuccess(Long userId, String username) {
        String details = "{\"username\":\"" + username + "\"}";
        return auditRepository.record(userId, "LOGIN_SUCCESS", "USER", String.valueOf(userId), details, "SUCCESS");
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLogEntry logLoginFailure(String username, String reason) {
        String details = "{\"username\":\"" + (username != null ? username : "unknown") + "\",\"reason\":\"" + reason + "\"}";
        return auditRepository.record(null, "LOGIN_FAILURE", "USER", username != null ? username : "unknown", details, "FAILURE");
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLogEntry logAdminAction(Long adminId, String action, String targetId, String detailsJson) {
        return auditRepository.record(adminId, action, "SYSTEM", targetId, detailsJson, "SUCCESS");
    }

    /**
     * Cryptographically verifies the integrity of the audit log hash chain.
     * Throws SecurityException if any link is broken, forged, or tampered with.
     */
    @Transactional(readOnly = true)
    public void verifyAuditLogChain() {
        List<AuditLogEntry> entries = auditRepository.findAllInOrder();
        if (entries.isEmpty()) {
            return;
        }

        String expectedPrevHash = HashUtils.GENESIS_HASH;

        for (AuditLogEntry entry : entries) {
            // 1. Verify prev_hash matches prior entry's hash (or GENESIS for entry #1)
            if (!entry.prevHash().equals(expectedPrevHash)) {
                throw new SecurityException(String.format(
                    "Audit chain integrity violation at entry ID %d: expected prev_hash %s but was %s",
                    entry.id(), expectedPrevHash, entry.prevHash()
                ));
            }

            // 2. Recompute hash from canonical fields
            String recomputedHash = AuditRepository.computeHash(
                entry.prevHash(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                entry.actorUserId(),
                entry.outcome(),
                entry.details(),
                entry.createdAt()
            );

            if (!entry.hash().equals(recomputedHash)) {
                throw new SecurityException(String.format(
                    "Audit payload tampering violation at entry ID %d: recomputed hash %s does not match recorded hash %s",
                    entry.id(), recomputedHash, entry.hash()
                ));
            }

            expectedPrevHash = entry.hash();
        }

        log.info("Audit log cryptographic chain verified successfully across {} entries.", entries.size());
    }
}

