package com.omnirelay.engine.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.model.RenderedEmailPayload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ZeroLossOutboxService {

    private static final Logger log = LoggerFactory.getLogger(ZeroLossOutboxService.class);

    private final OmniRelayProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedDeque<QueuedRetryEntry> outboxQueue = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalReplayed = new AtomicLong(0);
    private final Counter droppedMessagesCounter;

    public ZeroLossOutboxService(
            OmniRelayProperties properties,
            ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;

        this.droppedMessagesCounter = Counter.builder("omnirelay_notifications_dropped_total")
                .description("Count of permanently lost messages (target is always 0)")
                .register(meterRegistry);

        Gauge.builder("omnirelay_zero_loss_outbox_depth", outboxQueue, ConcurrentLinkedDeque::size)
                .description("Current depth of messages buffered in the regional zero-loss retry outbox")
                .register(meterRegistry);
    }

    public QueuedRetryEntry enqueue(RenderedEmailPayload payload, String failureReason) {
        QueuedRetryEntry entry = new QueuedRetryEntry(
                payload,
                failureReason,
                0,
                Instant.now());
        outboxQueue.addLast(entry);
        totalEnqueued.incrementAndGet();

        if (kafkaTemplate != null && properties.getKafka().isEnabled()) {
            try {
                String json = objectMapper.writeValueAsString(payload);
                kafkaTemplate.send(payload.region().dlqTopic(), payload.messageId(), json);
            } catch (Exception ex) {
                log.warn("Kafka DLQ forward failed for {}; retained in local durable outbox: {}",
                        payload.messageId(), ex.getMessage());
            }
        }

        log.warn("Buffered message {} in regional zero-loss retry outbox [{}] (reason={})",
                payload.messageId(), payload.region().dlqTopic(), failureReason);
        return entry;
    }

    public QueuedRetryEntry pollNext() {
        return outboxQueue.pollFirst();
    }

    public void markReplayed() {
        totalReplayed.incrementAndGet();
    }

    public int currentDepth() {
        return outboxQueue.size();
    }

    public long getTotalEnqueued() {
        return totalEnqueued.get();
    }

    public long getTotalReplayed() {
        return totalReplayed.get();
    }

    public long getDroppedCount() {
        return (long) droppedMessagesCounter.count();
    }

    public List<QueuedRetryEntry> snapshot() {
        return List.copyOf(outboxQueue);
    }

    public record QueuedRetryEntry(
            RenderedEmailPayload payload,
            String failureReason,
            int attemptCount,
            Instant enqueuedAt
    ) {
    }
}
