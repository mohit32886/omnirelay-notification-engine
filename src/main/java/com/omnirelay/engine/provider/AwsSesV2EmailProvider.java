package com.omnirelay.engine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.model.RenderedEmailPayload;
import com.omnirelay.engine.residency.ResidencyRegion;
import com.omnirelay.engine.security.VaultSecretRotationService;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AwsSesV2EmailProvider implements EmailProviderClient {

    private static final DateTimeFormatter AMZ_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final OmniRelayProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final VaultSecretRotationService vaultService;

    public AwsSesV2EmailProvider(
            OmniRelayProperties properties,
            HttpClient virtualThreadHttpClient,
            ObjectMapper objectMapper,
            VaultSecretRotationService vaultService) {
        this.properties = properties;
        this.httpClient = virtualThreadHttpClient;
        this.objectMapper = objectMapper;
        this.vaultService = vaultService;
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.AWS_SES_V2;
    }

    @Override
    public ProviderDeliveryResponse send(RenderedEmailPayload payload, ResidencyRegion region) {
        long startNs = System.nanoTime();
        String baseUrl = properties.getProviders().getSesEndpoints()
                .getOrDefault(region, "http://localhost:18082")
                .replaceAll("/+$", "");
        String endpoint = baseUrl + "/v2/email/outbound-emails";

        if (properties.getProviders().isSimulationMode()) {
            long latencyMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            return new ProviderDeliveryResponse(
                    ProviderType.AWS_SES_V2,
                    region,
                    "ses-" + UUID.randomUUID(),
                    200,
                    latencyMs,
                    endpoint);
        }

        try {
            String accessKeyId = vaultService.getCurrentSecret(VaultSecretRotationService.AWS_SES_ACCESS_KEY_ID);
            String secretAccessKey = vaultService.getCurrentSecret(VaultSecretRotationService.AWS_SES_SECRET_ACCESS_KEY);

            Map<String, Object> sesBody = Map.of(
                    "FromEmailAddress", payload.senderEmail(),
                    "Destination", Map.of("ToAddresses", List.of(payload.recipientEmail())),
                    "Content", Map.of(
                            "Simple", Map.of(
                                    "Subject", Map.of("Data", payload.subject(), "Charset", "UTF-8"),
                                    "Body", Map.of("Html", Map.of("Data", payload.sanitizedHtmlBody(), "Charset", "UTF-8"))
                            )
                    ),
                    "EmailTags", List.of(
                            Map.of("Name", "tenantId", "Value", payload.tenantId()),
                            Map.of("Name", "residencyRegion", "Value", region.awsRegion()),
                            Map.of("Name", "messageId", "Value", payload.messageId())
                    )
            );
            String json = objectMapper.writeValueAsString(sesBody);

            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            String amzDate = AMZ_DATE_FMT.format(now);
            String dateStamp = DATE_STAMP_FMT.format(now);
            String sigV4Auth = buildSigV4Authorization(accessKeyId, secretAccessKey, dateStamp, amzDate, region.awsRegion(), json);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("X-Amz-Date", amzDate)
                    .header("X-Amz-Region", region.awsRegion())
                    .header("Authorization", sigV4Auth)
                    .header("X-OmniRelay-Message-Id", payload.messageId())
                    .timeout(properties.getProviders().getRequestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderDeliveryException(
                        ProviderType.AWS_SES_V2,
                        response.statusCode(),
                        "AWS SESv2 rejected request with HTTP " + response.statusCode() + ": " + response.body());
            }

            String messageId = "ses-" + UUID.randomUUID();
            if (response.body() != null && !response.body().isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(response.body());
                    if (node.hasNonNull("MessageId")) {
                        messageId = node.get("MessageId").asText();
                    }
                } catch (Exception ignored) {
                    // Use generated ID if mock server returns plain text or empty body
                }
            }

            return new ProviderDeliveryResponse(
                    ProviderType.AWS_SES_V2,
                    region,
                    messageId,
                    response.statusCode(),
                    latencyMs,
                    endpoint);
        } catch (ProviderDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderDeliveryException(
                    ProviderType.AWS_SES_V2,
                    503,
                    "AWS SESv2 transport failure at " + endpoint + ": " + ex.getMessage(),
                    ex);
        }
    }

    private String buildSigV4Authorization(
            String accessKeyId,
            String secretAccessKey,
            String dateStamp,
            String amzDate,
            String awsRegion,
            String body) throws Exception {
        byte[] payloadHash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        String canonicalHash = HexFormat.of().formatHex(payloadHash);
        String scope = dateStamp + "/" + awsRegion + "/ses/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + canonicalHash;

        byte[] kDate = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, awsRegion);
        byte[] kService = hmacSha256(kRegion, "ses");
        byte[] kSigning = hmacSha256(kService, "aws4_request");
        String signature = HexFormat.of().formatHex(hmacSha256(kSigning, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=content-type;x-amz-date;x-amz-region, Signature=" + signature;
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
