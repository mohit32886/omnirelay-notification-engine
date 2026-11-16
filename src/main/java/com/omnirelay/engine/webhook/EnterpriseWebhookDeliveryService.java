package com.omnirelay.engine.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.model.OutboundWebhookRequest;
import com.omnirelay.engine.model.OutboundWebhookResult;
import com.omnirelay.engine.model.WebhookAttachment;
import com.omnirelay.engine.security.VaultSecretRotationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EnterpriseWebhookDeliveryService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/json",
            "text/csv",
            "text/plain",
            "image/png",
            "image/jpeg",
            "application/octet-stream",
            "application/xml"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuth2ClientCredentialsService oauth2Service;
    private final VaultSecretRotationService vaultService;
    private final OmniRelayProperties properties;
    private final MeterRegistry meterRegistry;

    public EnterpriseWebhookDeliveryService(
            HttpClient virtualThreadHttpClient,
            ObjectMapper objectMapper,
            OAuth2ClientCredentialsService oauth2Service,
            VaultSecretRotationService vaultService,
            OmniRelayProperties properties,
            MeterRegistry meterRegistry) {
        this.httpClient = virtualThreadHttpClient;
        this.objectMapper = objectMapper;
        this.oauth2Service = oauth2Service;
        this.vaultService = vaultService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public OutboundWebhookResult deliver(OutboundWebhookRequest request) {
        long startMs = System.currentTimeMillis();
        String deliveryId = (request.deliveryId() == null || request.deliveryId().isBlank())
                ? "wh-" + UUID.randomUUID()
                : request.deliveryId();

        List<Map<String, Object>> packagedAttachments = new ArrayList<>();
        List<String> sha256Digests = new ArrayList<>();
        for (WebhookAttachment attachment : request.attachments()) {
            PackagedAttachment packaged = validateAndPackageAttachment(attachment);
            packagedAttachments.add(packaged.asMap());
            sha256Digests.add(packaged.sha256());
        }

        boolean oauth2Used = request.oauth2TokenEndpoint() != null && !request.oauth2TokenEndpoint().isBlank();
        String bearerToken = null;
        if (oauth2Used) {
            if (request.oauth2ClientId() == null || request.oauth2ClientId().isBlank()) {
                throw new IllegalArgumentException("oauth2ClientId is required when oauth2TokenEndpoint is specified");
            }
            bearerToken = oauth2Service.getAccessToken(
                    request.oauth2TokenEndpoint(),
                    request.oauth2ClientId(),
                    request.oauth2ClientSecretOverride(),
                    request.oauth2Scope());
        }

        try {
            long epochSeconds = Instant.now().getEpochSecond();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("deliveryId", deliveryId);
            envelope.put("tenantId", request.tenantId());
            envelope.put("residencyRegion", request.tenantRegion().code());
            envelope.put("eventType", request.eventType());
            envelope.put("timestamp", epochSeconds);
            envelope.put("payload", request.payload());
            envelope.put("attachments", packagedAttachments);

            String jsonBody = objectMapper.writeValueAsString(envelope);
            String signedContent = epochSeconds + "." + jsonBody;
            String hmacHex = vaultService.signHmacSha256(VaultSecretRotationService.WEBHOOK_HMAC_KEY, signedContent);
            String signatureHeader = "t=" + epochSeconds + ",v1=" + hmacHex;

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(request.targetUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-OmniRelay-Delivery-Id", deliveryId)
                    .header("X-OmniRelay-Event-Type", request.eventType())
                    .header("X-OmniRelay-Residency-Region", request.tenantRegion().code())
                    .header("X-OmniRelay-Signature-256", signatureHeader)
                    .timeout(properties.getWebhook().getRequestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            if (bearerToken != null) {
                reqBuilder.header("Authorization", "Bearer " + bearerToken);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebhookDeliveryException(
                        response.statusCode(),
                        "Target webhook endpoint returned HTTP " + response.statusCode() + ": " + response.body());
            }

            Counter.builder("omnirelay_outbound_webhooks_delivered_total")
                    .tag("region", request.tenantRegion().code())
                    .tag("oauth2", Boolean.toString(oauth2Used))
                    .register(meterRegistry)
                    .increment();

            return new OutboundWebhookResult(
                    deliveryId,
                    request.tenantId(),
                    request.tenantRegion(),
                    request.targetUrl(),
                    response.statusCode(),
                    oauth2Used,
                    signatureHeader,
                    sha256Digests,
                    System.currentTimeMillis() - startMs,
                    Instant.now());
        } catch (WebhookDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WebhookDeliveryException(502, "Outbound webhook dispatch failed: " + ex.getMessage(), ex);
        }
    }

    private PackagedAttachment validateAndPackageAttachment(WebhookAttachment attachment) {
        String normalizedType = attachment.contentType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported webhook attachment MIME type: " + attachment.contentType());
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(attachment.base64Content());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Attachment base64Content is not valid Base64 for " + attachment.filename(), ex);
        }

        if (decoded.length > properties.getWebhook().getMaxAttachmentBytes()) {
            throw new IllegalArgumentException("Attachment " + attachment.filename()
                    + " exceeds max allowed bytes (" + properties.getWebhook().getMaxAttachmentBytes() + ")");
        }

        String computedSha256;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(decoded);
            computedSha256 = HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }

        if (attachment.expectedSha256() != null && !attachment.expectedSha256().isBlank()
                && !attachment.expectedSha256().trim().equalsIgnoreCase(computedSha256)) {
            throw new IllegalArgumentException("Attachment SHA-256 integrity mismatch for " + attachment.filename());
        }

        return new PackagedAttachment(
                attachment.filename(),
                normalizedType,
                decoded.length,
                computedSha256,
                attachment.base64Content());
    }

    private record PackagedAttachment(
            String filename,
            String contentType,
            int sizeBytes,
            String sha256,
            String base64Content
    ) {
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filename", filename);
            map.put("contentType", contentType);
            map.put("sizeBytes", sizeBytes);
            map.put("sha256", sha256);
            map.put("base64Content", base64Content);
            return map;
        }
    }
}
