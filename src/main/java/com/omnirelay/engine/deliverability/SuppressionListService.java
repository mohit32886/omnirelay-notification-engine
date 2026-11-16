package com.omnirelay.engine.deliverability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnirelay.engine.config.OmniRelayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SuppressionListService {

    private static final Logger log = LoggerFactory.getLogger(SuppressionListService.class);
    private static final String REDIS_KEY_PREFIX = "omnirelay:suppression:";

    private final OmniRelayProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Cache<String, SuppressionEntry> l1Cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();
    private final ConcurrentHashMap<String, SuppressionEntry> fallbackStore = new ConcurrentHashMap<>();

    public SuppressionListService(
            OmniRelayProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public SuppressionEntry suppress(
            String tenantId,
            String email,
            SuppressionReason reason,
            String sourceProvider,
            String diagnosticCode) {
        String normalizedEmail = normalizeEmail(email);
        String cacheKey = compositeKey(tenantId, normalizedEmail);
        SuppressionEntry entry = new SuppressionEntry(
                tenantId,
                normalizedEmail,
                reason,
                sourceProvider,
                diagnosticCode,
                Instant.now());

        l1Cache.put(cacheKey, entry);
        fallbackStore.put(cacheKey, entry);
        persistToRedis(tenantId, normalizedEmail, entry);

        Counter.builder("omnirelay_suppression_events_ingested_total")
                .tag("provider", sourceProvider == null ? "unknown" : sourceProvider)
                .tag("reason", reason.name())
                .register(meterRegistry)
                .increment();

        log.info("Suppressed recipient {} for tenant {} (reason={}, provider={})",
                normalizedEmail, tenantId, reason, sourceProvider);
        return entry;
    }

    public Optional<SuppressionEntry> findSuppression(String tenantId, String email) {
        String normalizedEmail = normalizeEmail(email);
        String cacheKey = compositeKey(tenantId, normalizedEmail);

        SuppressionEntry cached = l1Cache.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        SuppressionEntry fromRedis = fetchFromRedis(tenantId, normalizedEmail);
        if (fromRedis != null) {
            l1Cache.put(cacheKey, fromRedis);
            fallbackStore.put(cacheKey, fromRedis);
            return Optional.of(fromRedis);
        }

        SuppressionEntry fromFallback = fallbackStore.get(cacheKey);
        if (fromFallback != null) {
            l1Cache.put(cacheKey, fromFallback);
            return Optional.of(fromFallback);
        }
        return Optional.empty();
    }

    public boolean isSuppressed(String tenantId, String email) {
        Optional<SuppressionEntry> entry = findSuppression(tenantId, email);
        if (entry.isPresent()) {
            Counter.builder("omnirelay_suppressed_notifications_total")
                    .tag("reason", entry.get().reason().name())
                    .register(meterRegistry)
                    .increment();
            return true;
        }
        return false;
    }

    public List<SuppressionEntry> listSuppressions(String tenantId) {
        String prefix = tenantId + "::";
        return fallbackStore.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    public boolean removeSuppression(String tenantId, String email) {
        String normalizedEmail = normalizeEmail(email);
        String cacheKey = compositeKey(tenantId, normalizedEmail);
        l1Cache.invalidate(cacheKey);
        SuppressionEntry removed = fallbackStore.remove(cacheKey);
        if (redisTemplate != null && properties.getRedis().isEnabled()) {
            try {
                Long deleted = redisTemplate.opsForHash().delete(REDIS_KEY_PREFIX + tenantId, normalizedEmail);
                return removed != null || (deleted != null && deleted > 0);
            } catch (Exception ex) {
                log.debug("Redis delete failed for suppression {}: {}", cacheKey, ex.getMessage());
            }
        }
        return removed != null;
    }

    private void persistToRedis(String tenantId, String normalizedEmail, SuppressionEntry entry) {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForHash().put(REDIS_KEY_PREFIX + tenantId, normalizedEmail, json);
        } catch (Exception ex) {
            log.debug("Redis persistence skipped for suppression {}: {}", normalizedEmail, ex.getMessage());
        }
    }

    private SuppressionEntry fetchFromRedis(String tenantId, String normalizedEmail) {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return null;
        }
        try {
            Object raw = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + tenantId, normalizedEmail);
            if (raw != null) {
                return objectMapper.readValue(raw.toString(), SuppressionEntry.class);
            }
        } catch (Exception ex) {
            log.debug("Redis lookup skipped for suppression {}: {}", normalizedEmail, ex.getMessage());
        }
        return null;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String compositeKey(String tenantId, String normalizedEmail) {
        return tenantId + "::" + normalizedEmail;
    }
}
