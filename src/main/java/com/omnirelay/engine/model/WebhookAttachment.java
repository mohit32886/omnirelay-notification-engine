package com.omnirelay.engine.model;

import jakarta.validation.constraints.NotBlank;

public record WebhookAttachment(
        @NotBlank String filename,
        @NotBlank String contentType,
        @NotBlank String base64Content,
        String expectedSha256
) {
}
