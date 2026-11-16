package com.omnirelay.engine.failover;

import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyRegion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProviderRoutingStateService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRoutingStateService.class);
    private static final String REDIS_KEY_PREFIX = "omnirelay:routing:active-provider:";

    private final OmniRelayProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Counter failoverSwitchCounter;
    private final ConcurrentHashMap<ResidencyRegion, AtomicReference<ProviderType>> activeProviders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResidencyRegion, AtomicLong> degradationStartEpochMs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<FailoverEvent> failoverHistory = new CopyOnWriteArrayList<>();

    public ProviderRoutingStateService(
            OmniRelayProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.failoverSwitchCounter = Counter.builder("omnirelay_failover_switches_total")
                .description("Total active-passive provider failover transitions")
                .register(meterRegistry);

        for (ResidencyRegion region : ResidencyRegion.values()) {
            ProviderType initial = resolveInitialProvider(region, properties.getFailover().getDefaultPrimary());
            activeProviders.put(region, new AtomicReference<>(initial));
            Gauge.builder("omnirelay_active_provider_gauge", this,
                            svc -> svc.getActiveProvider(region) == ProviderType.SENDGRID ? 1.0 : 2.0)
                    .tag("region", region.code())
                    .description("Active email provider per region (1=SENDGRID, 2=AWS_SES_V2)")
                    .register(meterRegistry);
        }
    }

    @PostConstruct
    public void syncInitialFromRedis() {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return;
        }
        for (ResidencyRegion region : ResidencyRegion.values()) {
            try {
                String value = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + region.code());
                if (value != null) {
                    ProviderType parsed = ProviderType.valueOf(value);
                    if (region.supportsProvider(parsed)) {
                        activeProviders.get(region).set(parsed);
                    }
                } else {
                    persistToRedis(region, activeProviders.get(region).get());
                }
            } catch (Exception ex) {
                log.debug("Redis sync skipped for region {}: {}", region.code(), ex.getMessage());
            }
        }
    }

    public ProviderType getActiveProvider(ResidencyRegion region) {
        return activeProviders.get(region).get();
    }

    public Map<ResidencyRegion, ProviderType> getAllActiveProviders() {
        Map<ResidencyRegion, ProviderType> map = new EnumMap<>(ResidencyRegion.class);
        activeProviders.forEach((region, ref) -> map.put(region, ref.get()));
        return map;
    }

    public void markDegradationStart(ResidencyRegion region) {
        degradationStartEpochMs.computeIfAbsent(region, r -> new AtomicLong(System.currentTimeMillis()));
    }

    public synchronized boolean switchProvider(ResidencyRegion region, ProviderType target, String reason) {
        if (!region.supportsProvider(target)) {
            throw new IllegalArgumentException("Provider " + target + " is prohibited by residency policy for " + region);
        }
        AtomicReference<ProviderType> ref = activeProviders.get(region);
        ProviderType previous = ref.get();
        if (previous == target) {
            return false;
        }

        ref.set(target);
        persistToRedis(region, target);

        AtomicLong startRef = degradationStartEpochMs.remove(region);
        long mttrMs = startRef != null ? Math.max(1L, System.currentTimeMillis() - startRef.get()) : 1L;

        FailoverEvent event = new FailoverEvent(
                region,
                previous,
                target,
                reason,
                mttrMs,
                Instant.now());
        failoverHistory.add(event);
        failoverSwitchCounter.increment();

        log.warn("FAILOVER SWITCH [{}]: {} -> {} in {}ms (reason={})",
                region.code(), previous, target, mttrMs, reason);
        return true;
    }

    public List<FailoverEvent> getFailoverHistory() {
        List<FailoverEvent> copy = new ArrayList<>(failoverHistory);
        Collections.reverse(copy);
        return copy;
    }

    public void resetToDefault() {
        failoverHistory.clear();
        degradationStartEpochMs.clear();
        for (ResidencyRegion region : ResidencyRegion.values()) {
            ProviderType initial = resolveInitialProvider(region, properties.getFailover().getDefaultPrimary());
            activeProviders.get(region).set(initial);
            persistToRedis(region, initial);
        }
    }

    private ProviderType resolveInitialProvider(ResidencyRegion region, ProviderType defaultPrimary) {
        if (region.supportsProvider(defaultPrimary)) {
            return defaultPrimary;
        }
        return region.allowedProviders().iterator().next();
    }

    private void persistToRedis(ResidencyRegion region, ProviderType provider) {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + region.code(), provider.name());
        } catch (Exception ex) {
            log.debug("Redis routing persistence skipped for {}: {}", region.code(), ex.getMessage());
        }
    }

    public record FailoverEvent(
            ResidencyRegion region,
            ProviderType fromProvider,
            ProviderType toProvider,
            String reason,
            long mttrMs,
            Instant switchedAt
    ) {
    }
}
