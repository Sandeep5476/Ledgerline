package com.ledgerline.controller;

import com.ledgerline.dto.LedgerResult;
import com.ledgerline.dto.ReverseRequest;
import com.ledgerline.security.UserPrincipal;
import com.ledgerline.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final LedgerService ledgerService;
    private final com.ledgerline.service.IdempotencyService idempotencyService;
    private final com.ledgerline.service.AuditService auditService;

    public AdminController(
        LedgerService ledgerService,
        com.ledgerline.service.IdempotencyService idempotencyService,
        com.ledgerline.service.AuditService auditService
    ) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
    }

    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> adminPing(@AuthenticationPrincipal UserPrincipal principal) {
        auditService.logAdminAction(principal.id(), "ADMIN_PING", null, "{}");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Admin access verified"));
    }

    @PostMapping("/entries/{id}/reverse")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reverseEntry(
        @PathVariable("id") Long entryId,
        @Valid @RequestBody ReverseRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return idempotencyService.execute(
            idempotencyKey,
            principal.id(),
            "POST",
            "/api/admin/entries/" + entryId + "/reverse",
            request,
            () -> {
                LedgerResult result = ledgerService.reverse(
                    entryId,
                    principal.id(),
                    principal.role(),
                    request.reason(),
                    idempotencyKey
                );

                if (result.success()) {
                    return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "reversalEntryId", result.entry().id(),
                        "originalEntryId", entryId,
                        "postings", result.postings()
                    ));
                }

                HttpStatus status = switch (result.refusalCode()) {
                    case ALREADY_REVERSED -> HttpStatus.CONFLICT;
                    case REVERSAL_OVERDRAW -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case UNAUTHORIZED -> HttpStatus.FORBIDDEN;
                    case ACCOUNT_NOT_FOUND, INVALID_COMMAND -> HttpStatus.BAD_REQUEST;
                    default -> HttpStatus.BAD_REQUEST;
                };

                return ResponseEntity.status(status).body(Map.of(
                    "status", "REFUSED",
                    "refusalCode", result.refusalCode(),
                    "message", result.refusalReason(),
                    "attemptId", result.attempt().id()
                ));
            }
        );
    }
}


