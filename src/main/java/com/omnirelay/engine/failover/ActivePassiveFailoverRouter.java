package com.omnirelay.engine.failover;

import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.model.NotificationResult;
import com.omnirelay.engine.model.RenderedEmailPayload;
import com.omnirelay.engine.provider.EmailProviderClient;
import com.omnirelay.engine.provider.ProviderDeliveryException;
import com.omnirelay.engine.provider.ProviderDeliveryResponse;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyPolicyViolationException;
import com.omnirelay.engine.residency.ResidencyRegion;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ActivePassiveFailoverRouter {

    private static final Logger log = LoggerFactory.getLogger(ActivePassiveFailoverRouter.class);

    private final Map<ProviderType, EmailProviderClient> providerClients = new EnumMap<>(ProviderType.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderRoutingStateService routingStateService;
    private final ZeroLossOutboxService outboxService;
    private final OmniRelayProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong totalDeliveredCount = new AtomicLong(0);
    private final AtomicLong totalFailoverRoutedCount = new AtomicLong(0);

    public ActivePassiveFailoverRouter(
            List<EmailProviderClient> clients,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderRoutingStateService routingStateService,
            ZeroLossOutboxService outboxService,
            OmniRelayProperties properties,
            MeterRegistry meterRegistry) {
        for (EmailProviderClient client : clients) {
            this.providerClients.put(client.providerType(), client);
        }
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.routingStateService = routingStateService;
        this.outboxService = outboxService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;

        registerCircuitBreakerFailoverHooks();
    }

    private void registerCircuitBreakerFailoverHooks() {
        for (ProviderType provider : ProviderType.values()) {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(provider.circuitBreakerName());
            cb.getEventPublisher().onError(event -> {
                for (ResidencyRegion region : ResidencyRegion.values()) {
                    if (routingStateService.getActiveProvider(region) == provider) {
                        routingStateService.markDegradationStart(region);
                    }
                }
            });
            cb.getEventPublisher().onSlowCallRateExceeded(event -> {
                log.warn("Slow-call rate exceeded on {}: {}%", provider, event.getSlowCallRate());
                triggerAutomaticFailoverFrom(provider,
                        "Slow-call rate exceeded (" + String.format("%.1f", event.getSlowCallRate()) + "%)");
            });
            cb.getEventPublisher().onFailureRateExceeded(event -> {
                log.warn("Failure rate exceeded on {}: {}%", provider, event.getFailureRate());
                triggerAutomaticFailoverFrom(provider,
                        "Failure rate exceeded (" + String.format("%.1f", event.getFailureRate()) + "%)");
            });
            cb.getEventPublisher().onStateTransition(event -> {
                CircuitBreaker.State toState = event.getStateTransition().getToState();
                log.warn("CircuitBreaker {} transitioned: {}", cb.getName(), event.getStateTransition());
                if (toState == CircuitBreaker.State.OPEN || toState == CircuitBreaker.State.FORCED_OPEN) {
                    triggerAutomaticFailoverFrom(provider,
                            "CircuitBreaker " + cb.getName() + " transitioned to " + toState);
                }
            });
        }
    }

    public NotificationResult dispatch(RenderedEmailPayload payload) {
        ResidencyRegion region = payload.region();
        ProviderType primary = payload.preferredProvider() != null
                ? payload.preferredProvider()
                : routingStateService.getActiveProvider(region);

        if (!region.supportsProvider(primary)) {
            throw new ResidencyPolicyViolationException(
                    "Provider " + primary + " is disallowed for regulated region " + region);
        }

        long startMs = System.currentTimeMillis();
        try {
            ProviderDeliveryResponse response = executeWithCircuitBreaker(primary, payload, region);
            totalDeliveredCount.incrementAndGet();
            recordDeliveredMetric(primary, region, false, response.latencyMs());
            return new NotificationResult(
                    payload.messageId(),
                    payload.tenantId(),
                    region,
                    region.kafkaTopic(),
                    NotificationResult.DeliveryStatus.DELIVERED,
                    primary,
                    false,
                    null,
                    response.providerMessageId(),
                    System.currentTimeMillis() - startMs,
                    payload.htmlViolationsStripped(),
                    "Delivered via active provider " + primary,
                    Instant.now());
        } catch (Exception primaryEx) {
            recordFailedAttemptMetric(primary, region);
            routingStateService.markDegradationStart(region);

            ProviderType passive = primary.opposite();
            if (payload.preferredProvider() == null && region.supportsProvider(passive)) {
                CircuitBreaker primaryCb = circuitBreakerRegistry.circuitBreaker(primary.circuitBreakerName());
                if (properties.getFailover().isAutomaticFailoverEnabled()
                        && (primaryCb.getState() == CircuitBreaker.State.OPEN
                        || primaryCb.getMetrics().getFailureRate() >= properties.getFailover().getFailureRateThreshold()
                        || primaryEx instanceof CallNotPermittedException)) {
                    routingStateService.switchProvider(region, passive,
                            "In-flight active-passive failover from " + primary + ": " + primaryEx.getMessage());
                }

                try {
                    ProviderDeliveryResponse failoverResponse = executeWithCircuitBreaker(passive, payload, region);
                    totalDeliveredCount.incrementAndGet();
                    totalFailoverRoutedCount.incrementAndGet();
                    recordDeliveredMetric(passive, region, true, failoverResponse.latencyMs());
                    return new NotificationResult(
                            payload.messageId(),
                            payload.tenantId(),
                            region,
                            region.kafkaTopic(),
                            NotificationResult.DeliveryStatus.DELIVERED,
                            passive,
                            true,
                            null,
                            failoverResponse.providerMessageId(),
                            System.currentTimeMillis() - startMs,
                            payload.htmlViolationsStripped(),
                            "Zero-loss failover from " + primary + " to " + passive,
                            Instant.now());
                } catch (Exception secondaryEx) {
                    recordFailedAttemptMetric(passive, region);
                    return bufferInZeroLossOutbox(payload, region,
                            "Both " + primary + " and " + passive + " failed: " + secondaryEx.getMessage(),
                            startMs);
                }
            }

            return bufferInZeroLossOutbox(payload, region,
                    "Primary provider " + primary + " failed and no alternate allowed: " + primaryEx.getMessage(),
                    startMs);
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void replayZeroLossOutbox() {
        int maxBatch = Math.min(50, outboxService.currentDepth());
        for (int i = 0; i < maxBatch; i++) {
            ZeroLossOutboxService.QueuedRetryEntry entry = outboxService.pollNext();
            if (entry == null) {
                break;
            }
            RenderedEmailPayload payload = entry.payload();
            ResidencyRegion region = payload.region();
            ProviderType active = routingStateService.getActiveProvider(region);
            try {
                ProviderDeliveryResponse response = executeWithCircuitBreaker(active, payload, region);
                totalDeliveredCount.incrementAndGet();
                outboxService.markReplayed();
                recordDeliveredMetric(active, region, true, response.latencyMs());
            } catch (Exception ex) {
                ProviderType alternate = active.opposite();
                if (region.supportsProvider(alternate)) {
                    try {
                        ProviderDeliveryResponse altResponse = executeWithCircuitBreaker(alternate, payload, region);
                        totalDeliveredCount.incrementAndGet();
                        outboxService.markReplayed();
                        recordDeliveredMetric(alternate, region, true, altResponse.latencyMs());
                        continue;
                    } catch (Exception ignored) {
                        // Re-queue below without losing message
                    }
                }
                outboxService.enqueue(payload, "Retry deferred: " + ex.getMessage());
            }
        }
    }

    public FailoverStatusSnapshot getStatusSnapshot() {
        Map<String, CircuitBreakerSnapshot> circuits = new java.util.LinkedHashMap<>();
        for (ProviderType provider : ProviderType.values()) {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(provider.circuitBreakerName());
            CircuitBreaker.Metrics m = cb.getMetrics();
            circuits.put(provider.name(), new CircuitBreakerSnapshot(
                    cb.getName(),
                    cb.getState().name(),
                    m.getFailureRate(),
                    m.getSlowCallRate(),
                    m.getNumberOfBufferedCalls(),
                    m.getNumberOfFailedCalls(),
                    m.getNumberOfSlowCalls(),
                    m.getNumberOfNotPermittedCalls()
            ));
        }

        return new FailoverStatusSnapshot(
                routingStateService.getAllActiveProviders(),
                circuits,
                routingStateService.getFailoverHistory(),
                totalDeliveredCount.get(),
                totalFailoverRoutedCount.get(),
                outboxService.currentDepth(),
                outboxService.getTotalEnqueued(),
                outboxService.getTotalReplayed(),
                outboxService.getDroppedCount()
        );
    }

    public void resetCircuitBreakersAndRouting() {
        for (ProviderType provider : ProviderType.values()) {
            circuitBreakerRegistry.circuitBreaker(provider.circuitBreakerName()).reset();
        }
        routingStateService.resetToDefault();
    }

    private ProviderDeliveryResponse executeWithCircuitBreaker(
            ProviderType provider,
            RenderedEmailPayload payload,
            ResidencyRegion region) {
        EmailProviderClient client = providerClients.get(provider);
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(provider.circuitBreakerName());
        return cb.executeSupplier(() -> client.send(payload, region));
    }

    private void triggerAutomaticFailoverFrom(ProviderType failingProvider, String reason) {
        if (!properties.getFailover().isAutomaticFailoverEnabled()) {
            return;
        }
        ProviderType fallback = failingProvider.opposite();
        CircuitBreaker fallbackCb = circuitBreakerRegistry.circuitBreaker(fallback.circuitBreakerName());
        if (fallbackCb.getState() == CircuitBreaker.State.OPEN) {
            return;
        }
        for (ResidencyRegion region : ResidencyRegion.values()) {
            if (routingStateService.getActiveProvider(region) == failingProvider && region.supportsProvider(fallback)) {
                routingStateService.switchProvider(region, fallback, reason);
            }
        }
    }

    private NotificationResult bufferInZeroLossOutbox(
            RenderedEmailPayload payload,
            ResidencyRegion region,
            String reason,
            long startMs) {
        outboxService.enqueue(payload, reason);
        return new NotificationResult(
                payload.messageId(),
                payload.tenantId(),
                region,
                region.kafkaTopic(),
                NotificationResult.DeliveryStatus.QUEUED_ZERO_LOSS_RETRY,
                null,
                true,
                null,
                null,
                System.currentTimeMillis() - startMs,
                payload.htmlViolationsStripped(),
                "Buffered in zero-loss outbox: " + reason,
                Instant.now());
    }

    private void recordDeliveredMetric(ProviderType provider, ResidencyRegion region, boolean failover, long latencyMs) {
        Counter.builder("omnirelay_notifications_delivered_total")
                .tag("provider", provider.name())
                .tag("region", region.code())
                .tag("failover", Boolean.toString(failover))
                .register(meterRegistry)
                .increment();
        Timer.builder("omnirelay_provider_delivery_latency_ms")
                .tag("provider", provider.name())
                .tag("region", region.code())
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    private void recordFailedAttemptMetric(ProviderType provider, ResidencyRegion region) {
        Counter.builder("omnirelay_notifications_failed_provider_attempts_total")
                .tag("provider", provider.name())
                .tag("region", region.code())
                .register(meterRegistry)
                .increment();
    }

    public record CircuitBreakerSnapshot(
            String name,
            String state,
            float failureRate,
            float slowCallRate,
            int bufferedCalls,
            int failedCalls,
            int slowCalls,
            long notPermittedCalls
    ) {
    }

    public record FailoverStatusSnapshot(
            Map<ResidencyRegion, ProviderType> activeProviderByRegion,
            Map<String, CircuitBreakerSnapshot> circuitBreakers,
            List<ProviderRoutingStateService.FailoverEvent> failoverHistory,
            long totalDelivered,
            long totalFailoverRouted,
            int outboxDepth,
            long outboxEnqueued,
            long outboxReplayed,
            long droppedMessages
    ) {
    }
}
