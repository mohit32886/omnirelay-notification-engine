package com.omnirelay.engine.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.deliverability.HtmlSanitizerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TemplateCacheService {

    private static final Logger log = LoggerFactory.getLogger(TemplateCacheService.class);
    private static final String REDIS_PREFIX = "omnirelay:template:";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private final OmniRelayProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HtmlSanitizerService htmlSanitizerService;
    private final Counter l1HitCounter;
    private final Counter l2HitCounter;
    private final Counter missCounter;

    private final Cache<String, EmailTemplate> l1Cache = Caffeine.newBuilder()
            .maximumSize(25_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();
    private final ConcurrentHashMap<String, EmailTemplate> backingStore = new ConcurrentHashMap<>();

    public TemplateCacheService(
            OmniRelayProperties properties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectMapper objectMapper,
            HtmlSanitizerService htmlSanitizerService,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.htmlSanitizerService = htmlSanitizerService;
        this.l1HitCounter = Counter.builder("omnirelay_template_cache_requests_total")
                .tag("tier", "l1_hit")
                .register(meterRegistry);
        this.l2HitCounter = Counter.builder("omnirelay_template_cache_requests_total")
                .tag("tier", "l2_hit")
                .register(meterRegistry);
        this.missCounter = Counter.builder("omnirelay_template_cache_requests_total")
                .tag("tier", "miss")
                .register(meterRegistry);
    }

    public EmailTemplate registerTemplate(String tenantId, String templateId, String version, String subjectTemplate, String htmlTemplate) {
        String key = cacheKey(tenantId, templateId, version);
        HtmlSanitizerService.SanitizationResult cleanTemplate = htmlSanitizerService.sanitizeHtml(htmlTemplate);
        EmailTemplate template = new EmailTemplate(tenantId, templateId, version, subjectTemplate, cleanTemplate.sanitizedHtml(), Instant.now());
        l1Cache.put(key, template);
        backingStore.put(key, template);
        persistToRedis(key, template);
        return template;
    }

    public EmailTemplate getTemplate(String tenantId, String templateId, String version) {
        String key = cacheKey(tenantId, templateId, version);
        EmailTemplate l1 = l1Cache.getIfPresent(key);
        if (l1 != null) {
            l1HitCounter.increment();
            return l1;
        }
        EmailTemplate l2 = fetchFromRedis(key);
        if (l2 != null) {
            l2HitCounter.increment();
            l1Cache.put(key, l2);
            backingStore.put(key, l2);
            return l2;
        }
        EmailTemplate stored = backingStore.get(key);
        if (stored != null) {
            l2HitCounter.increment();
            l1Cache.put(key, stored);
            return stored;
        }
        missCounter.increment();
        throw new IllegalArgumentException("Template not found: " + key);
    }

    public RenderedTemplate render(
            String tenantId,
            String templateId,
            String version,
            String rawHtmlOverride,
            String subjectOverride,
            Map<String, String> variables) {

        String subjectPattern;
        String htmlSource;

        if (templateId != null && !templateId.isBlank()) {
            EmailTemplate template = getTemplate(tenantId, templateId, version);
            subjectPattern = (subjectOverride != null && !subjectOverride.isBlank())
                    ? subjectOverride
                    : template.subjectTemplate();
            htmlSource = template.sanitizedHtmlTemplate();
        } else {
            subjectPattern = subjectOverride;
            htmlSource = rawHtmlOverride == null ? "" : rawHtmlOverride;
        }

        String interpolatedSubject = interpolate(subjectPattern, variables, false);
        String interpolatedHtml = interpolate(htmlSource, variables, true);
        HtmlSanitizerService.SanitizationResult result = htmlSanitizerService.sanitizeHtml(interpolatedHtml);

        boolean variableViolations = variables != null && variables.values().stream()
                .anyMatch(v -> v != null && !v.equals(htmlSanitizerService.sanitizePlainVariable(v)));

        return new RenderedTemplate(
                interpolatedSubject,
                result.sanitizedHtml(),
                result.violationsStripped() || variableViolations
        );
    }

    public void evictL1ForTesting(String tenantId, String templateId, String version) {
        l1Cache.invalidate(cacheKey(tenantId, templateId, version));
    }

    private String interpolate(String pattern, Map<String, String> variables, boolean sanitizeValues) {
        if (pattern == null || pattern.isEmpty() || variables == null || variables.isEmpty()) {
            return pattern == null ? "" : pattern;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String rawVal = variables.getOrDefault(key, "");
            String safeVal = sanitizeValues ? htmlSanitizerService.sanitizePlainVariable(rawVal) : (rawVal == null ? "" : rawVal);
            matcher.appendReplacement(out, Matcher.quoteReplacement(safeVal));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private void persistToRedis(String key, EmailTemplate template) {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(REDIS_PREFIX + key, objectMapper.writeValueAsString(template), Duration.ofHours(24));
        } catch (Exception ex) {
            log.debug("Redis template store skipped for {}: {}", key, ex.getMessage());
        }
    }

    private EmailTemplate fetchFromRedis(String key) {
        if (redisTemplate == null || !properties.getRedis().isEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_PREFIX + key);
            if (json != null) {
                return objectMapper.readValue(json, EmailTemplate.class);
            }
        } catch (Exception ex) {
            log.debug("Redis template fetch skipped for {}: {}", key, ex.getMessage());
        }
        return null;
    }

    private String cacheKey(String tenantId, String templateId, String version) {
        return tenantId + ":" + templateId + ":" + (version == null || version.isBlank() ? "v1" : version);
    }

    public record EmailTemplate(
            String tenantId,
            String templateId,
            String version,
            String subjectTemplate,
            String sanitizedHtmlTemplate,
            Instant updatedAt
    ) {
    }

    public record RenderedTemplate(
            String subject,
            String sanitizedHtmlBody,
            boolean htmlViolationsStripped
    ) {
    }
}
