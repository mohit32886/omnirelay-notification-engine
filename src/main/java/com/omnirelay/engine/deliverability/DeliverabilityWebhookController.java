package com.omnirelay.engine.deliverability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.security.VaultSecretRotationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DeliverabilityWebhookController {

    private final SuppressionListService suppressionListService;
    private final VaultSecretRotationService vaultSecretRotationService;
    private final OmniRelayProperties properties;
    private final ObjectMapper objectMapper;

    public DeliverabilityWebhookController(
            SuppressionListService suppressionListService,
            VaultSecretRotationService vaultSecretRotationService,
            OmniRelayProperties properties,
            ObjectMapper objectMapper) {
        this.suppressionListService = suppressionListService;
        this.vaultSecretRotationService = vaultSecretRotationService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/inbound/sendgrid")
    public ResponseEntity<Map<String, Object>> ingestSendGridEvents(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-OmniRelay-Webhook-Signature", required = false) String signature,
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String defaultTenantId) throws Exception {

        if ((signature != null && !signature.isBlank()) || properties.getWebhook().isVerifyInboundSignature()) {
            boolean valid = vaultSecretRotationService.verifyHmacSha256(
                    VaultSecretRotationService.WEBHOOK_HMAC_KEY, rawPayload, signature);
            if (!valid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid webhook HMAC signature"));
            }
        }

        JsonNode root = objectMapper.readTree(rawPayload);
        List<SuppressionEntry> ingested = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode event : root) {
                SuppressionEntry entry = handleSendGridEvent(event, defaultTenantId);
                if (entry != null) {
                    ingested.add(entry);
                }
            }
        } else if (root.isObject()) {
            SuppressionEntry entry = handleSendGridEvent(root, defaultTenantId);
            if (entry != null) {
                ingested.add(entry);
            }
        }

        return ResponseEntity.ok(Map.of(
                "provider", "SENDGRID",
                "suppressedCount", ingested.size(),
                "entries", ingested
        ));
    }

    @PostMapping("/webhooks/inbound/ses")
    public ResponseEntity<Map<String, Object>> ingestSesEvents(
            @RequestBody String rawPayload,
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String defaultTenantId) throws Exception {

        JsonNode root = objectMapper.readTree(rawPayload);
        // Handle SNS wrapper envelope where actual SES notification is stringified in "Message"
        if (root.hasNonNull("Type") && "Notification".equals(root.path("Type").asText()) && root.has("Message")) {
            root = objectMapper.readTree(root.path("Message").asText());
        }

        String notificationType = root.path("notificationType").asText(root.path("eventType").asText(""));
        String tenantId = root.path("mail").path("tags").path("tenantId").isArray()
                && !root.path("mail").path("tags").path("tenantId").isEmpty()
                ? root.path("mail").path("tags").path("tenantId").get(0).asText(defaultTenantId)
                : defaultTenantId;

        List<SuppressionEntry> ingested = new ArrayList<>();
        switch (notificationType.toLowerCase(Locale.ROOT)) {
            case "bounce" -> {
                String bounceType = root.path("bounce").path("bounceType").asText("Permanent");
                if ("Permanent".equalsIgnoreCase(bounceType) || "Undetermined".equalsIgnoreCase(bounceType)) {
                    for (JsonNode recipient : root.path("bounce").path("bouncedRecipients")) {
                        String email = recipient.path("emailAddress").asText();
                        String diag = recipient.path("diagnosticCode").asText("SES permanent bounce");
                        if (!email.isBlank()) {
                            ingested.add(suppressionListService.suppress(
                                    tenantId, email, SuppressionReason.HARD_BOUNCE, "AWS_SES_V2", diag));
                        }
                    }
                }
            }
            case "complaint" -> {
                for (JsonNode recipient : root.path("complaint").path("complainedRecipients")) {
                    String email = recipient.path("emailAddress").asText();
                    String feedbackType = root.path("complaint").path("complaintFeedbackType").asText("abuse");
                    if (!email.isBlank()) {
                        ingested.add(suppressionListService.suppress(
                                tenantId, email, SuppressionReason.SPAM_COMPLAINT, "AWS_SES_V2", feedbackType));
                    }
                }
            }
            case "reject" -> {
                String reason = root.path("reject").path("reason").asText("SES policy reject");
                for (JsonNode dest : root.path("mail").path("destination")) {
                    String email = dest.asText();
                    if (!email.isBlank()) {
                        ingested.add(suppressionListService.suppress(
                                tenantId, email, SuppressionReason.MANUAL_BLOCK, "AWS_SES_V2", reason));
                    }
                }
            }
            default -> {
                // Non-suppression SES event (Delivery, Open, Click) - safely ignored
            }
        }

        return ResponseEntity.ok(Map.of(
                "provider", "AWS_SES_V2",
                "suppressedCount", ingested.size(),
                "entries", ingested
        ));
    }

    @GetMapping("/suppressions/{tenantId}")
    public ResponseEntity<List<SuppressionEntry>> listSuppressions(@PathVariable String tenantId) {
        return ResponseEntity.ok(suppressionListService.listSuppressions(tenantId));
    }

    @DeleteMapping("/suppressions/{tenantId}/{email}")
    public ResponseEntity<Map<String, Object>> removeSuppression(
            @PathVariable String tenantId,
            @PathVariable String email) {
        boolean removed = suppressionListService.removeSuppression(tenantId, email);
        return ResponseEntity.ok(Map.of("removed", removed, "tenantId", tenantId, "email", email));
    }

    private SuppressionEntry handleSendGridEvent(JsonNode event, String defaultTenantId) {
        String eventType = event.path("event").asText("").toLowerCase(Locale.ROOT);
        String email = event.path("email").asText("");
        if (email.isBlank()) {
            return null;
        }
        String tenantId = event.path("tenantId").asText(defaultTenantId);
        String reasonDetail = event.path("reason").asText(event.path("type").asText(eventType));

        SuppressionReason mappedReason = switch (eventType) {
            case "bounce", "dropped" -> SuppressionReason.HARD_BOUNCE;
            case "spamreport" -> SuppressionReason.SPAM_COMPLAINT;
            case "unsubscribe", "group_unsubscribe" -> SuppressionReason.UNSUBSCRIBE;
            default -> null;
        };

        if (mappedReason == null) {
            return null;
        }
        return suppressionListService.suppress(tenantId, email, mappedReason, "SENDGRID", reasonDetail);
    }
}
