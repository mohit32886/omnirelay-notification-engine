package com.omnirelay.engine.residency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.deliverability.SuppressionEntry;
import com.omnirelay.engine.deliverability.SuppressionListService;
import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.model.NotificationRequest;
import com.omnirelay.engine.model.NotificationResult;
import com.omnirelay.engine.model.RenderedEmailPayload;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.template.TemplateCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DataResidencyPolicyRouter {

    private static final Logger log = LoggerFactory.getLogger(DataResidencyPolicyRouter.class);

    private final OmniRelayProperties properties;
    private final SuppressionListService suppressionListService;
    private final TemplateCacheService templateCacheService;
    private final ActivePassiveFailoverRouter failoverRouter;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public DataResidencyPolicyRouter(
            OmniRelayProperties properties,
            SuppressionListService suppressionListService,
            TemplateCacheService templateCacheService,
            ActivePassiveFailoverRouter failoverRouter,
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.suppressionListService = suppressionListService;
        this.templateCacheService = templateCacheService;
        this.failoverRouter = failoverRouter;
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public NotificationResult routeAndProcess(NotificationRequest request) {
        long startMs = System.currentTimeMillis();
        ResidencyRegion region = request.tenantRegion();
        String messageId = (request.messageId() == null || request.messageId().isBlank())
                ? "msg-" + UUID.randomUUID()
                : request.messageId();

        validateResidencyPolicy(region, request.preferredProvider());

        Counter.builder("omnirelay_residency_routed_total")
                .tag("region", region.code())
                .tag("topic", region.kafkaTopic())
                .register(meterRegistry)
                .increment();

        Optional<SuppressionEntry> suppression = suppressionListService.findSuppression(
                request.tenantId(), request.recipientEmail());
        if (suppression.isPresent()) {
            SuppressionEntry entry = suppression.get();
            suppressionListService.isSuppressed(request.tenantId(), request.recipientEmail());
            return new NotificationResult(
                    messageId,
                    request.tenantId(),
                    region,
                    region.kafkaTopic(),
                    NotificationResult.DeliveryStatus.SUPPRESSED,
                    null,
                    false,
                    entry.reason(),
                    null,
                    System.currentTimeMillis() - startMs,
                    false,
                    "Suppressed prior to provider dispatch (" + entry.reason() + ": " + entry.diagnosticCode() + ")",
                    Instant.now());
        }

        TemplateCacheService.RenderedTemplate rendered = templateCacheService.render(
                request.tenantId(),
                request.templateId(),
                request.templateVersion(),
                request.rawHtmlBody(),
                request.subject(),
                request.variables());

        RenderedEmailPayload payload = new RenderedEmailPayload(
                messageId,
                request.tenantId(),
                region,
                request.recipientEmail(),
                request.senderEmail(),
                rendered.subject(),
                rendered.sanitizedHtmlBody(),
                rendered.htmlViolationsStripped(),
                request.preferredProvider());

        if (properties.getKafka().isEnabled() && kafkaTemplate != null) {
            publishToRegionalKafkaTopic(payload);
            if (properties.getKafka().isAsyncDispatch()) {
                return new NotificationResult(
                        messageId,
                        request.tenantId(),
                        region,
                        region.kafkaTopic(),
                        NotificationResult.DeliveryStatus.QUEUED_KAFKA,
                        null,
                        false,
                        null,
                        null,
                        System.currentTimeMillis() - startMs,
                        payload.htmlViolationsStripped(),
                        "Pinned and published to regional Kafka topic " + region.kafkaTopic(),
                        Instant.now());
            }
        }

        return failoverRouter.dispatch(payload);
    }

    public void validateResidencyPolicy(ResidencyRegion region, ProviderType preferredProvider) {
        if (region == null) {
            throw new ResidencyPolicyViolationException("Tenant residency region is required");
        }
        if (preferredProvider != null && !region.supportsProvider(preferredProvider)) {
            incrementViolationMetric(region);
            throw new ResidencyPolicyViolationException(
                    "Residency violation: provider " + preferredProvider
                            + " is not authorized for regulated region " + region.code()
                            + " (allowed: " + region.allowedProviders() + ")");
        }
        if (region == ResidencyRegion.US_GOV
                && properties.getResidency().isStrictGovCloudPinning()
                && preferredProvider == ProviderType.SENDGRID) {
            incrementViolationMetric(region);
            throw new ResidencyPolicyViolationException(
                    "FedRAMP GovCloud policy pins US_GOV tenants strictly to AWS_SES_V2 in us-gov-west-1");
        }
    }

    public String resolveRegionalTopic(ResidencyRegion region) {
        return region.kafkaTopic();
    }

    private void publishToRegionalKafkaTopic(RenderedEmailPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    payload.region().kafkaTopic(),
                    payload.tenantId() + ":" + payload.messageId(),
                    json);
            record.headers().add("X-OmniRelay-Residency-Region",
                    payload.region().code().getBytes(StandardCharsets.UTF_8));
            record.headers().add("X-OmniRelay-Aws-Region",
                    payload.region().awsRegion().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
        } catch (Exception ex) {
            log.warn("Kafka regional publish skipped/fallback for topic {} ({})",
                    payload.region().kafkaTopic(), ex.getMessage());
        }
    }

    private void incrementViolationMetric(ResidencyRegion region) {
        Counter.builder("omnirelay_residency_policy_violations_total")
                .tag("region", region.code())
                .register(meterRegistry)
                .increment();
    }
}
