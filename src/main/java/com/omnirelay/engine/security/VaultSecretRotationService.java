package com.omnirelay.engine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VaultSecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(VaultSecretRotationService.class);

    public static final String SENDGRID_API_KEY = "sendgrid.api-key";
    public static final String AWS_SES_ACCESS_KEY_ID = "aws.ses.access-key-id";
    public static final String AWS_SES_SECRET_ACCESS_KEY = "aws.ses.secret-access-key";
    public static final String WEBHOOK_HMAC_KEY = "webhook.hmac-signing-key";
    public static final String OAUTH2_CLIENT_SECRET = "oauth2.client-secret";

    private final OmniRelayProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Counter rotationCounter;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, VersionedSecret> secretStore = new ConcurrentHashMap<>();

    public VaultSecretRotationService(
            OmniRelayProperties properties,
            HttpClient virtualThreadHttpClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.httpClient = virtualThreadHttpClient;
        this.objectMapper = objectMapper;
        this.rotationCounter = Counter.builder("omnirelay_vault_secret_rotations_total")
                .description("Total number of HashiCorp Vault dynamic secret rotations executed")
                .register(meterRegistry);
    }

    @PostConstruct
    public void initializeSecrets() {
        OmniRelayProperties.Vault vault = properties.getVault();
        Instant now = Instant.now();
        secretStore.put(SENDGRID_API_KEY, new VersionedSecret(vault.getInitialSendgridApiKey(), null, 1, now, now));
        secretStore.put(AWS_SES_ACCESS_KEY_ID, new VersionedSecret(vault.getInitialAwsAccessKeyId(), null, 1, now, now));
        secretStore.put(AWS_SES_SECRET_ACCESS_KEY, new VersionedSecret(vault.getInitialAwsSecretAccessKey(), null, 1, now, now));
        secretStore.put(WEBHOOK_HMAC_KEY, new VersionedSecret(vault.getInitialWebhookHmacKey(), null, 1, now, now));
        secretStore.put(OAUTH2_CLIENT_SECRET, new VersionedSecret(vault.getInitialOauth2ClientSecret(), null, 1, now, now));

        if (vault.isEnabled()) {
            pullFromVaultIfAvailable();
            pushSecretsToVaultIfAvailable();
        }
    }

    public String getCurrentSecret(String key) {
        VersionedSecret secret = secretStore.get(key);
        if (secret == null) {
            throw new IllegalArgumentException("Unknown secret key: " + key);
        }
        return secret.currentValue();
    }

    public VersionedSecret rotateSecret(String key) {
        VersionedSecret updated = secretStore.compute(key, (k, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Unknown secret key: " + k);
            }
            String nextValue = generateSecretValue(k, existing.version() + 1);
            Instant now = Instant.now();
            Instant graceUntil = now.plus(properties.getVault().getGraceWindow());
            return new VersionedSecret(nextValue, existing.currentValue(), existing.version() + 1, now, graceUntil);
        });
        rotationCounter.increment();
        if (properties.getVault().isEnabled()) {
            pushSecretsToVaultIfAvailable();
        }
        log.info("Rotated Vault secret '{}' to version v{} (previous secret valid until {})",
                key, updated.version(), updated.graceUntil());
        return updated;
    }

    public Map<String, SecretMetadata> rotateAllSecrets() {
        for (String key : secretStore.keySet()) {
            rotateSecret(key);
        }
        return getSecretMetadata();
    }

    @Scheduled(fixedDelayString = "${omnirelay.vault.rotation-interval-ms:21600000}")
    public void scheduledRotation() {
        if (properties.getVault().isEnabled()) {
            rotateSecret(WEBHOOK_HMAC_KEY);
            rotateSecret(OAUTH2_CLIENT_SECRET);
        }
    }

    public String signHmacSha256(String secretKeyName, String payload) {
        String key = getCurrentSecret(secretKeyName);
        return computeHmacHex(key, payload);
    }

    public boolean verifyHmacSha256(String secretKeyName, String payload, String providedSignatureHex) {
        if (providedSignatureHex == null || providedSignatureHex.isBlank()) {
            return false;
        }
        VersionedSecret secret = secretStore.get(secretKeyName);
        if (secret == null) {
            return false;
        }
        String normalized = providedSignatureHex.trim();
        if (normalized.startsWith("sha256=")) {
            normalized = normalized.substring("sha256=".length());
        }
        String expectedCurrent = computeHmacHex(secret.currentValue(), payload);
        if (constantTimeEquals(expectedCurrent, normalized)) {
            return true;
        }
        if (secret.previousValue() != null && Instant.now().isBefore(secret.graceUntil())) {
            String expectedPrevious = computeHmacHex(secret.previousValue(), payload);
            return constantTimeEquals(expectedPrevious, normalized);
        }
        return false;
    }

    public Map<String, SecretMetadata> getSecretMetadata() {
        Map<String, SecretMetadata> result = new LinkedHashMap<>();
        secretStore.forEach((key, val) -> result.put(key, new SecretMetadata(
                key,
                val.version(),
                mask(val.currentValue()),
                val.previousValue() != null && Instant.now().isBefore(val.graceUntil()),
                val.rotatedAt(),
                val.graceUntil()
        )));
        return result;
    }

    private String generateSecretValue(String key, int nextVersion) {
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return switch (key) {
            case SENDGRID_API_KEY -> "SG.v" + nextVersion + "." + token;
            case AWS_SES_ACCESS_KEY_ID -> "AKIAV" + nextVersion + token.substring(0, 12).toUpperCase().replace('-', 'A').replace('_', 'B');
            case WEBHOOK_HMAC_KEY -> "whsec_v" + nextVersion + "_" + token;
            case OAUTH2_CLIENT_SECRET -> "oauth2_v" + nextVersion + "_" + token;
            default -> "vault_v" + nextVersion + "_" + token;
        };
    }

    private String computeHmacHex(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private String mask(String secret) {
        if (secret == null || secret.length() <= 6) {
            return "***";
        }
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }

    private void pullFromVaultIfAvailable() {
        OmniRelayProperties.Vault vault = properties.getVault();
        try {
            String url = vault.getUri().replaceAll("/+$", "") + "/v1/" + vault.getKvMount() + "/data/" + vault.getSecretPath();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("X-Vault-Token", vault.getToken())
                    .timeout(Duration.ofMillis(1000))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode data = objectMapper.readTree(response.body()).path("data").path("data");
                if (data.isObject()) {
                    Instant now = Instant.now();
                    data.fields().forEachRemaining(entry -> {
                        String key = entry.getKey();
                        String value = entry.getValue().asText();
                        if (secretStore.containsKey(key) && !value.isBlank()) {
                            secretStore.put(key, new VersionedSecret(value, null, 1, now, now));
                        }
                    });
                    log.info("Loaded runtime secrets from HashiCorp Vault at {}", url);
                }
            }
        } catch (Exception ex) {
            log.debug("HashiCorp Vault unreachable during bootstrap ({}); using local managed secrets", ex.getMessage());
        }
    }

    private void pushSecretsToVaultIfAvailable() {
        OmniRelayProperties.Vault vault = properties.getVault();
        try {
            Map<String, String> payloadData = new LinkedHashMap<>();
            secretStore.forEach((k, v) -> payloadData.put(k, v.currentValue()));
            String body = objectMapper.writeValueAsString(Map.of("data", payloadData));
            String url = vault.getUri().replaceAll("/+$", "") + "/v1/" + vault.getKvMount() + "/data/" + vault.getSecretPath();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("X-Vault-Token", vault.getToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(1000))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            log.debug("HashiCorp Vault push skipped ({})", ex.getMessage());
        }
    }

    public record VersionedSecret(
            String currentValue,
            String previousValue,
            int version,
            Instant rotatedAt,
            Instant graceUntil
    ) {
    }

    public record SecretMetadata(
            String key,
            int version,
            String maskedValue,
            boolean previousVersionInGraceWindow,
            Instant rotatedAt,
            Instant graceUntil
    ) {
    }
}
