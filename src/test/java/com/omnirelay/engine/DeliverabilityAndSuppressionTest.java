package com.omnirelay.engine;

import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.deliverability.HtmlSanitizerService;
import com.omnirelay.engine.deliverability.SuppressionListService;
import com.omnirelay.engine.deliverability.SuppressionReason;
import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.model.NotificationRequest;
import com.omnirelay.engine.model.NotificationResult;
import com.omnirelay.engine.residency.DataResidencyPolicyRouter;
import com.omnirelay.engine.residency.ResidencyRegion;
import com.omnirelay.engine.security.VaultSecretRotationService;
import com.omnirelay.engine.template.TemplateCacheService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeliverabilityAndSuppressionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SuppressionListService suppressionListService;

    @Autowired
    private HtmlSanitizerService htmlSanitizerService;

    @Autowired
    private TemplateCacheService templateCacheService;

    @Autowired
    private VaultSecretRotationService vaultSecretRotationService;

    @Autowired
    private DataResidencyPolicyRouter policyRouter;

    @Autowired
    private ActivePassiveFailoverRouter failoverRouter;

    @Autowired
    private OmniRelayProperties properties;

    private MockWebServer mockProviderServer;

    @BeforeEach
    void setUp() throws Exception {
        mockProviderServer = new MockWebServer();
        mockProviderServer.enqueue(new MockResponse().setResponseCode(202).addHeader("X-Message-Id", "sg-1"));
        mockProviderServer.enqueue(new MockResponse().setResponseCode(202).addHeader("X-Message-Id", "sg-2"));
        mockProviderServer.start();

        String baseUrl = mockProviderServer.url("/").toString().replaceAll("/+$", "");
        for (ResidencyRegion region : ResidencyRegion.values()) {
            properties.getProviders().getSendgridEndpoints().put(region, baseUrl);
            properties.getProviders().getSesEndpoints().put(region, baseUrl);
        }
        failoverRouter.resetCircuitBreakersAndRouting();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockProviderServer.shutdown();
    }

    @Test
    void ingestsSendGridAndSesBounceComplaintWebhooksAndBlocksSubsequentSends() throws Exception {
        String sgWebhookBody = """
                [
                  {"event":"bounce","email":"hardbounce@example.com","tenantId":"tenant-deliver","reason":"550 5.1.1 User unknown"},
                  {"event":"unsubscribe","email":"optout@example.com","tenantId":"tenant-deliver"}
                ]
                """;

        mockMvc.perform(post("/api/v1/webhooks/inbound/sendgrid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sgWebhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressedCount").value(2));

        String sesSnsEnvelope = """
                {
                  "Type": "Notification",
                  "Message": "{\\"notificationType\\":\\"Complaint\\",\\"mail\\":{\\"tags\\":{\\"tenantId\\":[\\"tenant-deliver\\"]}},\\"complaint\\":{\\"complainedRecipients\\":[{\\"emailAddress\\":\\"spammer@example.com\\"}],\\"complaintFeedbackType\\":\\"abuse\\"}}"
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks/inbound/ses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sesSnsEnvelope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressedCount").value(1));

        assertThat(suppressionListService.findSuppression("tenant-deliver", "hardbounce@example.com"))
                .isPresent()
                .get()
                .extracting(s -> s.reason())
                .isEqualTo(SuppressionReason.HARD_BOUNCE);

        NotificationResult suppressedResult = policyRouter.routeAndProcess(new NotificationRequest(
                "msg-suppressed-1",
                "tenant-deliver",
                ResidencyRegion.US_EAST,
                "spammer@example.com",
                "noreply@omnirelay.io",
                "Promo",
                null,
                null,
                "<p>Hello</p>",
                Map.of(),
                null));

        assertThat(suppressedResult.status()).isEqualTo(NotificationResult.DeliveryStatus.SUPPRESSED);
        assertThat(suppressedResult.suppressionReason()).isEqualTo(SuppressionReason.SPAM_COMPLAINT);
        assertThat(mockProviderServer.getRequestCount()).isZero();
    }

    @Test
    void sanitizesHtmlInjectionVectorsAndCachesTemplatesAcrossL1AndL2() {
        templateCacheService.registerTemplate(
                "tenant-sec",
                "welcome-tpl",
                "v1",
                "Welcome {{name}}",
                "<table><tr><td>Hello {{name}}</td></tr></table><script>alert('xss')</script><img src=\"https://cdn.omnirelay.io/logo.png\" onerror=\"stealCookie()\"><a href=\"javascript:evil()\">Click</a>");

        // Evict L1 to verify L2 fallback cache hit works seamlessly
        templateCacheService.evictL1ForTesting("tenant-sec", "welcome-tpl", "v1");

        NotificationResult result = policyRouter.routeAndProcess(new NotificationRequest(
                "msg-xss-1",
                "tenant-sec",
                ResidencyRegion.US_EAST,
                "safe@example.com",
                "noreply@omnirelay.io",
                "",
                "welcome-tpl",
                "v1",
                null,
                Map.of("name", "<img src=x onerror=alert(1)>Alice"),
                null));

        assertThat(result.status()).isEqualTo(NotificationResult.DeliveryStatus.DELIVERED);
        assertThat(result.htmlSanitized()).isTrue();

        var sanitized = htmlSanitizerService.sanitizeHtml(
                "<p>Hi</p><iframe src='https://evil.test'></iframe><a href='https://omnirelay.io'>Valid</a>");
        assertThat(sanitized.violationsStripped()).isTrue();
        assertThat(sanitized.sanitizedHtml()).doesNotContain("<iframe").contains("https://omnirelay.io");
    }

    @Test
    void rotatesVaultSecretsWithZeroDowntimeGraceWindow() throws Exception {
        String payload = "[{\"event\":\"bounce\",\"email\":\"vaulttest@example.com\",\"tenantId\":\"tenant-vault\"}]";
        String sigWithV1 = vaultSecretRotationService.signHmacSha256(
                VaultSecretRotationService.WEBHOOK_HMAC_KEY, payload);

        var rotated = vaultSecretRotationService.rotateSecret(VaultSecretRotationService.WEBHOOK_HMAC_KEY);
        assertThat(rotated.version()).isGreaterThanOrEqualTo(2);

        String sigWithV2 = vaultSecretRotationService.signHmacSha256(
                VaultSecretRotationService.WEBHOOK_HMAC_KEY, payload);
        assertThat(sigWithV2).isNotEqualTo(sigWithV1);

        // Both v1 (within grace window) and v2 signatures must succeed; invalid signature must fail 401
        mockMvc.perform(post("/api/v1/webhooks/inbound/sendgrid")
                        .header("X-OmniRelay-Webhook-Signature", sigWithV1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/webhooks/inbound/sendgrid")
                        .header("X-OmniRelay-Webhook-Signature", sigWithV2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/webhooks/inbound/sendgrid")
                        .header("X-OmniRelay-Webhook-Signature", "deadbeef0000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }
}
