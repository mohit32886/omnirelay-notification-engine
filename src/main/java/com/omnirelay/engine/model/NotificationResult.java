package com.omnirelay.engine.model;

import com.omnirelay.engine.deliverability.SuppressionReason;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyRegion;

import java.time.Instant;

public record NotificationResult(
        String messageId,
        String tenantId,
        ResidencyRegion region,
        String kafkaTopic,
        DeliveryStatus status,
        ProviderType providerUsed,
        boolean failoverTriggered,
        SuppressionReason suppressionReason,
        String providerMessageId,
        long latencyMs,
        boolean htmlSanitized,
        String detail,
        Instant processedAt
) {
    public enum DeliveryStatus {
        DELIVERED,
        QUEUED_KAFKA,
        QUEUED_ZERO_LOSS_RETRY,
        SUPPRESSED
    }
}
