package com.omnirelay.engine.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.model.RenderedEmailPayload;
import com.omnirelay.engine.residency.ResidencyRegion;
import com.omnirelay.engine.security.VaultSecretRotationService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SendGridEmailProvider implements EmailProviderClient {

    private final OmniRelayProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final VaultSecretRotationService vaultService;

    public SendGridEmailProvider(
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
        return ProviderType.SENDGRID;
    }

    @Override
    public ProviderDeliveryResponse send(RenderedEmailPayload payload, ResidencyRegion region) {
        long startNs = System.nanoTime();
        String baseUrl = properties.getProviders().getSendgridEndpoints()
                .getOrDefault(region, "http://localhost:18081")
                .replaceAll("/+$", "");
        String endpoint = baseUrl + "/v3/mail/send";

        if (properties.getProviders().isSimulationMode()) {
            long latencyMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);
            return new ProviderDeliveryResponse(
                    ProviderType.SENDGRID,
                    region,
                    "sg-" + UUID.randomUUID(),
                    202,
                    latencyMs,
                    endpoint);
        }

        try {
            String apiKey = vaultService.getCurrentSecret(VaultSecretRotationService.SENDGRID_API_KEY);
            Map<String, Object> sgBody = Map.of(
                    "personalizations", List.of(Map.of(
                            "to", List.of(Map.of("email", payload.recipientEmail())),
                            "custom_args", Map.of(
                                    "messageId", payload.messageId(),
                                    "tenantId", payload.tenantId(),
                                    "region", region.code()))),
                    "from", Map.of("email", payload.senderEmail()),
                    "subject", payload.subject(),
                    "content", List.of(Map.of(
                            "type", "text/html",
                            "value", payload.sanitizedHtmlBody()))
            );
            String json = objectMapper.writeValueAsString(sgBody);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-OmniRelay-Region", region.code())
                    .header("X-OmniRelay-Message-Id", payload.messageId())
                    .timeout(properties.getProviders().getRequestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Math.max(1, (System.nanoTime() - startNs) / 1_000_000);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderDeliveryException(
                        ProviderType.SENDGRID,
                        response.statusCode(),
                        "SendGrid rejected request with HTTP " + response.statusCode() + ": " + response.body());
            }

            String providerMsgId = response.headers().firstValue("X-Message-Id")
                    .orElse("sg-" + UUID.randomUUID());

            return new ProviderDeliveryResponse(
                    ProviderType.SENDGRID,
                    region,
                    providerMsgId,
                    response.statusCode(),
                    latencyMs,
                    endpoint);
        } catch (ProviderDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderDeliveryException(
                    ProviderType.SENDGRID,
                    503,
                    "SendGrid transport failure at " + endpoint + ": " + ex.getMessage(),
                    ex);
        }
    }
}
