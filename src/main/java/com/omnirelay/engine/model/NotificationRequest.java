package com.omnirelay.engine.model;

import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyRegion;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record NotificationRequest(
        String messageId,
        @NotBlank String tenantId,
        @NotNull ResidencyRegion tenantRegion,
        @NotBlank @Email String recipientEmail,
        @NotBlank @Email String senderEmail,
        @NotBlank String subject,
        String templateId,
        String templateVersion,
        String rawHtmlBody,
        Map<String, String> variables,
        ProviderType preferredProvider
) {
    public NotificationRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        templateVersion = (templateVersion == null || templateVersion.isBlank()) ? "v1" : templateVersion;
    }
}
