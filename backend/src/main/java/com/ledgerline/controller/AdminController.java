package com.ledgerline.controller;

import com.ledgerline.dto.*;
import com.ledgerline.security.UserPrincipal;
import com.ledgerline.service.AccountService;
import com.ledgerline.service.AuditService;
import com.ledgerline.service.IdempotencyService;
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
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final AccountService accountService;
    private final com.ledgerline.service.SystemVerificationService systemVerificationService;

    public AdminController(
        LedgerService ledgerService,
        IdempotencyService idempotencyService,
        AuditService auditService,
        AccountService accountService,
        com.ledgerline.service.SystemVerificationService systemVerificationService
    ) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.accountService = accountService;
        this.systemVerificationService = systemVerificationService;
    }

    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> adminPing(@AuthenticationPrincipal UserPrincipal principal) {
        auditService.logAdminAction(principal.id(), "ADMIN_PING", null, "{}");
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Admin access verified"));
    }

    @GetMapping("/verify")
    @PreAuthorize("hasRole('ADMIN') or hasRole('AUDITOR')")
    public ResponseEntity<SystemVerificationReport> verifySystem() {
        return ResponseEntity.ok(systemVerificationService.verifySystemIntegrity());
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountDto> createAccount(
        @Valid @RequestBody CreateAccountRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        AccountDto account = accountService.createAccount(request.name(), principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @PostMapping("/accounts/{id}/members")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> addMember(
        @PathVariable("id") Long accountId,
        @Valid @RequestBody AddMemberRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        accountService.addMember(accountId, request.userId(), principal.id());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "User " + request.userId() + " added as member to account " + accountId));
    }

    @PostMapping("/accounts/{id}/adjust")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> adjust(
        @PathVariable("id") Long accountId,
        @Valid @RequestBody AdjustRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        boolean isCredit = request.direction() == null || !"DEBIT".equalsIgnoreCase(request.direction());
        return idempotencyService.execute(
            idempotencyKey,
            principal.id(),
            "POST",
            "/api/admin/accounts/" + accountId + "/adjust",
            request,
            () -> {
                LedgerResult result = ledgerService.adjust(
                    accountId,
                    request.amount(),
                    isCredit,
                    principal.id(),
                    principal.role(),
                    request.reason(),
                    idempotencyKey
                );

                if (result.success()) {
                    return ResponseEntity.ok(Map.of(
                        "status", "SUCCESS",
                        "entryId", result.entry().id(),
                        "kind", result.entry().kind(),
                        "postings", result.postings()
                    ));
                }

                HttpStatus status = switch (result.refusalCode()) {
                    case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
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


