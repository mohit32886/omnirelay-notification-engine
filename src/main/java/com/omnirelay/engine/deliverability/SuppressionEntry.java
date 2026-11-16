package com.omnirelay.engine.deliverability;

import java.time.Instant;

public record SuppressionEntry(
        String tenantId,
        String email,
        SuppressionReason reason,
        String sourceProvider,
        String diagnosticCode,
        Instant suppressedAt
) {
}
