package com.ledgerline.controller;

import com.ledgerline.dto.AmountRequest;
import com.ledgerline.dto.LedgerResult;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.security.UserPrincipal;
import com.ledgerline.service.IdempotencyService;
import com.ledgerline.service.LedgerService;
import com.ledgerline.service.StatementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountMovementController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final StatementService statementService;

    public AccountMovementController(
        LedgerService ledgerService,
        IdempotencyService idempotencyService,
        StatementService statementService
    ) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.statementService = statementService;
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<?> deposit(
        @PathVariable("id") Long accountId,
        @Valid @RequestBody AmountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return idempotencyService.execute(
            idempotencyKey,
            principal.id(),
            "POST",
            "/api/accounts/" + accountId + "/deposit",
            request,
            () -> {
                LedgerResult result = ledgerService.deposit(
                    accountId,
                    request.amount(),
                    principal.id(),
                    principal.role(),
                    request.reason(),
                    idempotencyKey
                );
                return mapResultToResponse(result);
            }
        );
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdraw(
        @PathVariable("id") Long accountId,
        @Valid @RequestBody AmountRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return idempotencyService.execute(
            idempotencyKey,
            principal.id(),
            "POST",
            "/api/accounts/" + accountId + "/withdraw",
            request,
            () -> {
                LedgerResult result = ledgerService.withdraw(
                    accountId,
                    request.amount(),
                    principal.id(),
                    principal.role(),
                    request.reason(),
                    idempotencyKey
                );
                return mapResultToResponse(result);
            }
        );
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementResponse> getStatement(
        @PathVariable("id") Long accountId,
        @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
        @RequestParam(value = "size", required = false, defaultValue = "20") Integer size,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        StatementResponse response = statementService.getStatement(
            accountId,
            from,
            to,
            page,
            size,
            principal.id(),
            principal.role()
        );
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> mapResultToResponse(LedgerResult result) {
        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "entryId", result.entry().id(),
                "kind", result.entry().kind(),
                "postings", result.postings()
            ));
        }

        HttpStatus status = switch (result.refusalCode()) {
            case UNAUTHORIZED, ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND; // Caller cannot see/debit unowned accounts
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_COMMAND -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity.status(status).body(Map.of(
            "status", "REFUSED",
            "refusalCode", result.refusalCode(),
            "message", result.refusalReason(),
            "attemptId", result.attempt().id()
        ));
    }
}

