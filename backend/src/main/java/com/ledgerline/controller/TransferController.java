package com.ledgerline.controller;

import com.ledgerline.dto.LedgerResult;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.security.UserPrincipal;
import com.ledgerline.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final LedgerService ledgerService;
    private final com.ledgerline.service.IdempotencyService idempotencyService;

    public TransferController(
        LedgerService ledgerService,
        com.ledgerline.service.IdempotencyService idempotencyService
    ) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> transfer(
        @Valid @RequestBody TransferRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return idempotencyService.execute(
            idempotencyKey,
            principal.id(),
            "POST",
            "/api/transfers",
            request,
            () -> {
                LedgerResult result = ledgerService.transfer(
                    request.fromAccountId(),
                    request.toAccountId(),
                    request.amount(),
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
                    case UNAUTHORIZED, ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
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
        );
    }
}

