package com.omnirelay.engine;

import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.residency.ResidencyRegion;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataResidencyAndWebhookTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OmniRelayProperties properties;

    @Autowired
    private ActivePassiveFailoverRouter failoverRouter;

    private MockWebServer regionalMockServer;
    private MockWebServer oauthAndWebhookServer;

    private final AtomicReference<RecordedRequest> capturedTokenRequest = new AtomicReference<>();
    private final AtomicReference<RecordedRequest> capturedWebhookRequest = new AtomicReference<>();
    private final AtomicReference<String> capturedWebhookBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        regionalMockServer = new MockWebServer();
        regionalMockServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(200)
                        .addHeader("X-Message-Id", "regional-ok-1")
                        .setBody("{\"MessageId\":\"ses-gov-ok-1\"}");
            }
        });
        regionalMockServer.start();

        oauthAndWebhookServer = new MockWebServer();
        oauthAndWebhookServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/oauth2/token".equals(request.getPath())) {
                    capturedTokenRequest.set(request);
                    return new MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"jwt-oauth2-bearer-xyz\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                }
                if ("/webhooks/enterprise".equals(request.getPath())) {
                    capturedWebhookRequest.set(request);
                    capturedWebhookBody.set(request.getBody().readUtf8());
                    return new MockResponse().setResponseCode(200).setBody("{\"accepted\":true}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        oauthAndWebhookServer.start();

        String regUrl = regionalMockServer.url("/").toString().replaceAll("/+$", "");
        for (ResidencyRegion region : ResidencyRegion.values()) {
            properties.getProviders().getSendgridEndpoints().put(region, regUrl);
            properties.getProviders().getSesEndpoints().put(region, regUrl);
        }
        failoverRouter.resetCircuitBreakersAndRouting();
    }

    @AfterEach
    void tearDown() throws Exception {
        regionalMockServer.shutdown();
        oauthAndWebhookServer.shutdown();
    }

    @Test
    void pinsRegulatedTenantsToRegionalTopicsAndBlocksGovCloudViolations() throws Exception {
        String euPayload = """
                {
                  "tenantId": "eu-bank-01",
                  "tenantRegion": "EU",
                  "recipientEmail": "compliance@eu-bank.de",
                  "senderEmail": "alerts@omnirelay.io",
                  "subject": "GDPR Statement",
                  "rawHtmlBody": "<p>EU statement attached</p>"
                }
                """;
        mockMvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(euPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("EU"))
                .andExpect(jsonPath("$.kafkaTopic").value("omnirelay.notifications.eu.v1"));

        String apPayload = """
                {
                  "tenantId": "jp-fintech-01",
                  "tenantRegion": "AP_NORTHEAST",
                  "recipientEmail": "ops@tokyo-pay.jp",
                  "senderEmail": "alerts@omnirelay.io",
                  "subject": "APAC Audit",
                  "rawHtmlBody": "<p>APAC alert</p>"
                }
                """;
        mockMvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(apPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("AP_NORTHEAST"))
                .andExpect(jsonPath("$.kafkaTopic").value("omnirelay.notifications.ap-northeast.v1"));

        String govPayload = """
                {
                  "tenantId": "fed-agency-01",
                  "tenantRegion": "US_GOV",
                  "recipientEmail": "sec@agency.gov",
                  "senderEmail": "alerts@omnirelay.io",
                  "subject": "GovCloud Alert",
                  "rawHtmlBody": "<p>FedRAMP compliant</p>"
                }
                """;
        mockMvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(govPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("US_GOV"))
                .andExpect(jsonPath("$.kafkaTopic").value("omnirelay.notifications.us-gov.v1"))
                .andExpect(jsonPath("$.providerUsed").value("AWS_SES_V2"));

        // Violating GovCloud residency by requesting SENDGRID must fail with 422
        String govViolationPayload = """
                {
                  "tenantId": "fed-agency-01",
                  "tenantRegion": "US_GOV",
                  "recipientEmail": "sec@agency.gov",
                  "senderEmail": "alerts@omnirelay.io",
                  "subject": "Illegal Provider",
                  "rawHtmlBody": "<p>Should block</p>",
                  "preferredProvider": "SENDGRID"
                }
                """;
        mockMvc.perform(post("/api/v1/notifications/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(govViolationPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RESIDENCY_POLICY_VIOLATION"));
    }

    @Test
    void deliversEnterpriseWebhookWithOAuth2ClientCredentialsHmacAndRichAttachments() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\nOmniRelay Compliance Report\n%%EOF".getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(pdfBytes);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdfBytes));

        String tokenUrl = oauthAndWebhookServer.url("/oauth2/token").toString();
        String targetUrl = oauthAndWebhookServer.url("/webhooks/enterprise").toString();

        String webhookRequest = """
                {
                  "tenantId": "eu-enterprise-99",
                  "tenantRegion": "EU",
                  "targetUrl": "%s",
                  "eventType": "notification.delivered",
                  "payload": {"notificationId": "n-7788", "status": "DELIVERED"},
                  "oauth2TokenEndpoint": "%s",
                  "oauth2ClientId": "omnirelay-enterprise-client",
                  "oauth2Scope": "webhooks:write",
                  "attachments": [
                    {
                      "filename": "audit-receipt.pdf",
                      "contentType": "application/pdf",
                      "base64Content": "%s",
                      "expectedSha256": "%s"
                    }
                  ]
                }
                """.formatted(targetUrl, tokenUrl, b64, expectedSha);

        mockMvc.perform(post("/api/v1/webhooks/outbound/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oauth2Authenticated").value(true))
                .andExpect(jsonPath("$.httpStatus").value(200))
                .andExpect(jsonPath("$.attachmentSha256Digests[0]").value(expectedSha));

        assertThat(capturedTokenRequest.get()).isNotNull();
        assertThat(capturedWebhookRequest.get()).isNotNull();
        assertThat(capturedWebhookRequest.get().getHeader("Authorization")).isEqualTo("Bearer jwt-oauth2-bearer-xyz");
        assertThat(capturedWebhookRequest.get().getHeader("X-OmniRelay-Residency-Region")).isEqualTo("eu");
        assertThat(capturedWebhookRequest.get().getHeader("X-OmniRelay-Signature-256")).startsWith("t=").contains(",v1=");
        assertThat(capturedWebhookBody.get()).contains("audit-receipt.pdf").contains(expectedSha);

        // Tampered attachment digest must be rejected with HTTP 400
        String tamperedRequest = webhookRequest.replace(expectedSha, "0000000000000000000000000000000000000000000000000000000000000000");
        mockMvc.perform(post("/api/v1/webhooks/outbound/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedRequest))
                .andExpect(status().isBadRequest());
    }
}
