package com.omnirelay.engine.model;

import com.omnirelay.engine.residency.ResidencyRegion;

import java.time.Instant;
import java.util.List;

public record OutboundWebhookResult(
        String deliveryId,
        String tenantId,
        ResidencyRegion region,
        String targetUrl,
        int httpStatus,
        boolean oauth2Authenticated,
        String signatureHeader,
        List<String> attachmentSha256Digests,
        long latencyMs,
        Instant deliveredAt
) {
}
