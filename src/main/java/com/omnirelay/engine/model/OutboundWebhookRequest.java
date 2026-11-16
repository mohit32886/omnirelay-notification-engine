package com.omnirelay.engine.model;

import com.omnirelay.engine.residency.ResidencyRegion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record OutboundWebhookRequest(
        String deliveryId,
        @NotBlank String tenantId,
        @NotNull ResidencyRegion tenantRegion,
        @NotBlank String targetUrl,
        @NotBlank String eventType,
        @NotNull Map<String, Object> payload,
        String oauth2TokenEndpoint,
        String oauth2ClientId,
        String oauth2ClientSecretOverride,
        String oauth2Scope,
        @Valid List<WebhookAttachment> attachments
) {
    public OutboundWebhookRequest {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
