package com.omnirelay.engine.api;

import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.failover.ProviderRoutingStateService;
import com.omnirelay.engine.model.NotificationRequest;
import com.omnirelay.engine.model.NotificationResult;
import com.omnirelay.engine.model.OutboundWebhookRequest;
import com.omnirelay.engine.model.OutboundWebhookResult;
import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.DataResidencyPolicyRouter;
import com.omnirelay.engine.residency.ResidencyRegion;
import com.omnirelay.engine.security.VaultSecretRotationService;
import com.omnirelay.engine.template.TemplateCacheService;
import com.omnirelay.engine.webhook.EnterpriseWebhookDeliveryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final DataResidencyPolicyRouter policyRouter;
    private final EnterpriseWebhookDeliveryService webhookDeliveryService;
    private final TemplateCacheService templateCacheService;
    private final ActivePassiveFailoverRouter failoverRouter;
    private final ProviderRoutingStateService routingStateService;
    private final VaultSecretRotationService vaultService;
    private final MeterRegistry meterRegistry;

    public NotificationController(
            DataResidencyPolicyRouter policyRouter,
            EnterpriseWebhookDeliveryService webhookDeliveryService,
            TemplateCacheService templateCacheService,
            ActivePassiveFailoverRouter failoverRouter,
            ProviderRoutingStateService routingStateService,
            VaultSecretRotationService vaultService,
            MeterRegistry meterRegistry) {
        this.policyRouter = policyRouter;
        this.webhookDeliveryService = webhookDeliveryService;
        this.templateCacheService = templateCacheService;
        this.failoverRouter = failoverRouter;
        this.routingStateService = routingStateService;
        this.vaultService = vaultService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/notifications/send")
    public ResponseEntity<NotificationResult> sendNotification(@Valid @RequestBody NotificationRequest request) {
        return ResponseEntity.ok(policyRouter.routeAndProcess(request));
    }

    @PostMapping("/webhooks/outbound/send")
    public ResponseEntity<OutboundWebhookResult> sendOutboundWebhook(@Valid @RequestBody OutboundWebhookRequest request) {
        return ResponseEntity.ok(webhookDeliveryService.deliver(request));
    }

    @PostMapping("/templates")
    public ResponseEntity<TemplateCacheService.EmailTemplate> registerTemplate(
            @Valid @RequestBody RegisterTemplateRequest request) {
        return ResponseEntity.ok(templateCacheService.registerTemplate(
                request.tenantId(),
                request.templateId(),
                request.version() == null || request.version().isBlank() ? "v1" : request.version(),
                request.subjectTemplate(),
                request.htmlTemplate()));
    }

    @GetMapping("/failover/status")
    public ResponseEntity<ActivePassiveFailoverRouter.FailoverStatusSnapshot> getFailoverStatus() {
        return ResponseEntity.ok(failoverRouter.getStatusSnapshot());
    }

    @PostMapping("/failover/switch")
    public ResponseEntity<Map<String, Object>> switchProvider(@RequestBody SwitchProviderRequest request) {
        boolean switched = routingStateService.switchProvider(
                request.region(),
                request.targetProvider(),
                request.reason() == null ? "Manual operator/gameday switch" : request.reason());
        return ResponseEntity.ok(Map.of(
                "switched", switched,
                "region", request.region(),
                "activeProvider", routingStateService.getActiveProvider(request.region())));
    }

    @PostMapping("/failover/reset")
    public ResponseEntity<ActivePassiveFailoverRouter.FailoverStatusSnapshot> resetFailover() {
        failoverRouter.resetCircuitBreakersAndRouting();
        return ResponseEntity.ok(failoverRouter.getStatusSnapshot());
    }

    @PostMapping("/secrets/rotate")
    public ResponseEntity<Map<String, VaultSecretRotationService.SecretMetadata>> rotateSecrets(
            @RequestBody(required = false) RotateSecretRequest request) {
        if (request != null && request.secretKey() != null && !request.secretKey().isBlank()) {
            vaultService.rotateSecret(request.secretKey());
            return ResponseEntity.ok(vaultService.getSecretMetadata());
        }
        return ResponseEntity.ok(vaultService.rotateAllSecrets());
    }

    @GetMapping("/secrets/metadata")
    public ResponseEntity<Map<String, VaultSecretRotationService.SecretMetadata>> getSecretMetadata() {
        return ResponseEntity.ok(vaultService.getSecretMetadata());
    }

    @GetMapping("/metrics/summary")
    public ResponseEntity<Map<String, Object>> getMetricsSummary() {
        ActivePassiveFailoverRouter.FailoverStatusSnapshot status = failoverRouter.getStatusSnapshot();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDelivered", status.totalDelivered());
        summary.put("totalFailoverRouted", status.totalFailoverRouted());
        summary.put("outboxDepth", status.outboxDepth());
        summary.put("outboxEnqueued", status.outboxEnqueued());
        summary.put("outboxReplayed", status.outboxReplayed());
        summary.put("droppedMessages", status.droppedMessages());
        summary.put("activeProviderByRegion", status.activeProviderByRegion());
        summary.put("circuitBreakers", status.circuitBreakers());
        summary.put("failoverSwitches", sumCounter("omnirelay_failover_switches_total"));
        summary.put("suppressedNotifications", sumCounter("omnirelay_suppressed_notifications_total"));
        summary.put("sanitizationViolations", sumCounter("omnirelay_html_sanitization_violations_total"));
        summary.put("residencyViolations", sumCounter("omnirelay_residency_policy_violations_total"));
        summary.put("vaultRotations", sumCounter("omnirelay_vault_secret_rotations_total"));
        summary.put("outboundWebhooksDelivered", sumCounter("omnirelay_outbound_webhooks_delivered_total"));
        return ResponseEntity.ok(summary);
    }

    private double sumCounter(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    public record RegisterTemplateRequest(
            @NotBlank String tenantId,
            @NotBlank String templateId,
            String version,
            @NotBlank String subjectTemplate,
            @NotBlank String htmlTemplate
    ) {
    }

    public record SwitchProviderRequest(
            ResidencyRegion region,
            ProviderType targetProvider,
            String reason
    ) {
    }

    public record RotateSecretRequest(String secretKey) {
    }
}
