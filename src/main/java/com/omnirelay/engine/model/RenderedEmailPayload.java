package com.omnirelay.engine.model;

import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyRegion;

public record RenderedEmailPayload(
        String messageId,
        String tenantId,
        ResidencyRegion region,
        String recipientEmail,
        String senderEmail,
        String subject,
        String sanitizedHtmlBody,
        boolean htmlViolationsStripped,
        ProviderType preferredProvider
) {
}
