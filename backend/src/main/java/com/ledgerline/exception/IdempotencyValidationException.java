package com.ledgerline.exception;

public class IdempotencyValidationException extends RuntimeException {
    public IdempotencyValidationException(String message) {
        super(message);
    }
}

