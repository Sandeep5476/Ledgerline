package com.ledgerline.dto;

import java.time.Instant;
import java.util.Map;

public record SystemVerificationReport(
    String status, // "PASSED" or "FAILED"
    Instant timestamp,
    Map<String, CheckResult> checks
) {
    public record CheckResult(
        String status, // "PASS" or "FAIL"
        String detail,
        Object data
    ) {
        public static CheckResult pass(String detail, Object data) {
            return new CheckResult("PASS", detail, data);
        }

        public static CheckResult fail(String detail, Object data) {
            return new CheckResult("FAIL", detail, data);
        }
    }
}

