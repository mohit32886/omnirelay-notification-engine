package com.omnirelay.engine;

import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.failover.ProviderRoutingStateService;
import com.omnirelay.engine.model.NotificationRequest;
import com.omnirelay.engine.model.NotificationResult;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.DataResidencyPolicyRouter;
import com.omnirelay.engine.residency.ResidencyRegion;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ActivePassiveFailoverRouterTest {

    @Autowired
    private DataResidencyPolicyRouter policyRouter;

    @Autowired
    private ActivePassiveFailoverRouter failoverRouter;

    @Autowired
    private ProviderRoutingStateService routingStateService;

    @Autowired
    private OmniRelayProperties properties;

    private MockWebServer sendGridServer;
    private MockWebServer sesServer;

    private final AtomicBoolean sendGridBlackout = new AtomicBoolean(false);
    private final AtomicBoolean sesBlackout = new AtomicBoolean(false);
    private final AtomicInteger sendGridCalls = new AtomicInteger(0);
    private final AtomicInteger sesCalls = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        sendGridBlackout.set(false);
        sesBlackout.set(false);
        sendGridCalls.set(0);
        sesCalls.set(0);

        sendGridServer = new MockWebServer();
        sendGridServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                sendGridCalls.incrementAndGet();
                if (sendGridBlackout.get()) {
                    return new MockResponse().setResponseCode(503).setBody("{\"errors\":[{\"message\":\"SendGrid outage\"}]}");
                }
                return new MockResponse()
                        .setResponseCode(202)
                        .addHeader("X-Message-Id", "sg-ok-" + sendGridCalls.get())
                        .setBody("{}");
            }
        });
        sendGridServer.start();

        sesServer = new MockWebServer();
        sesServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                sesCalls.incrementAndGet();
                if (sesBlackout.get()) {
                    return new MockResponse().setResponseCode(500).setBody("{\"message\":\"SES outage\"}");
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"MessageId\":\"ses-ok-" + sesCalls.get() + "\"}");
            }
        });
        sesServer.start();

        String sgUrl = sendGridServer.url("/").toString().replaceAll("/+$", "");
        String sesUrl = sesServer.url("/").toString().replaceAll("/+$", "");
        for (ResidencyRegion region : ResidencyRegion.values()) {
            properties.getProviders().getSendgridEndpoints().put(region, sgUrl);
            properties.getProviders().getSesEndpoints().put(region, sesUrl);
        }

        failoverRouter.resetCircuitBreakersAndRouting();
    }

    @AfterEach
    void tearDown() throws Exception {
        sendGridServer.shutdown();
        sesServer.shutdown();
    }

    @Test
    void failsOverFromSendGridToAwsSesV2Under30sWithZeroLostMessages() throws Exception {
        // 1. Steady-state delivery uses SendGrid for EU
        NotificationResult initial = policyRouter.routeAndProcess(new NotificationRequest(
                "msg-steady-1",
                "tenant-eu-fin",
                ResidencyRegion.EU,
                "user1@example.com",
                "noreply@omnirelay.io",
                "Welcome",
                null,
                null,
                "<p>Hello steady state</p>",
                Map.of(),
                null));

        assertThat(initial.status()).isEqualTo(NotificationResult.DeliveryStatus.DELIVERED);
        assertThat(initial.providerUsed()).isEqualTo(ProviderType.SENDGRID);
        assertThat(initial.failoverTriggered()).isFalse();

        // 2. Simulate complete SendGrid blackout while blasting 40 concurrent requests on Java 21 virtual threads
        sendGridBlackout.set(true);
        long blackoutStartMs = System.currentTimeMillis();

        List<NotificationResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NotificationResult>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> policyRouter.routeAndProcess(new NotificationRequest(
                        "msg-blackout-" + idx,
                        "tenant-eu-fin",
                        ResidencyRegion.EU,
                        "user" + idx + "@example.com",
                        "noreply@omnirelay.io",
                        "Urgent Alert " + idx,
                        null,
                        null,
                        "<p>Alert " + idx + "</p>",
                        Map.of(),
                        null))));
            }
            for (Future<NotificationResult> f : futures) {
                results.add(f.get());
            }
        }

        long elapsedMs = System.currentTimeMillis() - blackoutStartMs;

        // Assert 0 lost messages and < 30s failover
        assertThat(results).hasSize(40);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(NotificationResult.DeliveryStatus.DELIVERED);
            assertThat(r.providerUsed()).isEqualTo(ProviderType.AWS_SES_V2);
        });
        assertThat(elapsedMs).isLessThan(30_000L);

        ActivePassiveFailoverRouter.FailoverStatusSnapshot snapshot = failoverRouter.getStatusSnapshot();
        assertThat(snapshot.activeProviderByRegion().get(ResidencyRegion.EU)).isEqualTo(ProviderType.AWS_SES_V2);
        assertThat(snapshot.circuitBreakers().get("SENDGRID").state()).isEqualTo("OPEN");
        assertThat(snapshot.droppedMessages()).isZero();
        assertThat(snapshot.failoverHistory()).isNotEmpty();
        assertThat(snapshot.failoverHistory().get(0).mttrMs()).isLessThan(30_000L);
    }

    @Test
    void buffersInZeroLossOutboxWhenBothProvidersFailAndReplaysOnRecovery() {
        sendGridBlackout.set(true);
        sesBlackout.set(true);

        NotificationResult buffered = policyRouter.routeAndProcess(new NotificationRequest(
                "msg-dual-outage-1",
                "tenant-us",
                ResidencyRegion.US_EAST,
                "vip@example.com",
                "noreply@omnirelay.io",
                "Invoice Ready",
                null,
                null,
                "<p>Invoice #123</p>",
                Map.of(),
                null));

        assertThat(buffered.status()).isEqualTo(NotificationResult.DeliveryStatus.QUEUED_ZERO_LOSS_RETRY);
        assertThat(failoverRouter.getStatusSnapshot().outboxDepth()).isEqualTo(1);
        assertThat(failoverRouter.getStatusSnapshot().droppedMessages()).isZero();

        // Recover SESv2 and replay outbox
        sesBlackout.set(false);
        failoverRouter.replayZeroLossOutbox();

        ActivePassiveFailoverRouter.FailoverStatusSnapshot afterReplay = failoverRouter.getStatusSnapshot();
        assertThat(afterReplay.outboxDepth()).isZero();
        assertThat(afterReplay.outboxReplayed()).isGreaterThanOrEqualTo(1);
        assertThat(afterReplay.droppedMessages()).isZero();
    }
}
