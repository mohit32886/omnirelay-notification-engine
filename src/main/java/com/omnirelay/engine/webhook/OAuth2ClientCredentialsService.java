package com.omnirelay.engine.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.security.VaultSecretRotationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OAuth2ClientCredentialsService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final VaultSecretRotationService vaultService;
    private final OmniRelayProperties properties;
    private final Counter tokenExchangeCounter;
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public OAuth2ClientCredentialsService(
            HttpClient virtualThreadHttpClient,
            ObjectMapper objectMapper,
            VaultSecretRotationService vaultService,
            OmniRelayProperties properties,
            MeterRegistry meterRegistry) {
        this.httpClient = virtualThreadHttpClient;
        this.objectMapper = objectMapper;
        this.vaultService = vaultService;
        this.properties = properties;
        this.tokenExchangeCounter = Counter.builder("omnirelay_oauth2_token_exchanges_total")
                .description("Total OAuth 2.0 client_credentials token acquisitions")
                .register(meterRegistry);
    }

    public String getAccessToken(String tokenEndpoint, String clientId, String clientSecretOverride, String scope) {
        String effectiveSecret = (clientSecretOverride != null && !clientSecretOverride.isBlank())
                ? clientSecretOverride
                : vaultService.getCurrentSecret(VaultSecretRotationService.OAUTH2_CLIENT_SECRET);

        String cacheKey = tokenEndpoint + "|" + clientId + "|" + (scope == null ? "" : scope);
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && Instant.now().plusSeconds(30).isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }

        CachedToken fresh = fetchNewToken(tokenEndpoint, clientId, effectiveSecret, scope);
        tokenCache.put(cacheKey, fresh);
        return fresh.accessToken();
    }

    public void clearCache() {
        tokenCache.clear();
    }

    private CachedToken fetchNewToken(String tokenEndpoint, String clientId, String clientSecret, String scope) {
        try {
            StringBuilder form = new StringBuilder();
            form.append("grant_type=client_credentials");
            form.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            form.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
            if (scope != null && !scope.isBlank()) {
                form.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(properties.getWebhook().getRequestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebhookDeliveryException(
                        response.statusCode(),
                        "OAuth2 token endpoint returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String accessToken = json.path("access_token").asText("");
            if (accessToken.isBlank()) {
                throw new WebhookDeliveryException(502, "OAuth2 response missing access_token");
            }
            long expiresIn = json.path("expires_in").asLong(3600);
            tokenExchangeCounter.increment();
            return new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn)));
        } catch (WebhookDeliveryException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WebhookDeliveryException(502, "OAuth2 client_credentials exchange failed: " + ex.getMessage(), ex);
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
