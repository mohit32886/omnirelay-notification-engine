package com.omnirelay.engine.residency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnirelay.engine.config.OmniRelayProperties;
import com.omnirelay.engine.failover.ActivePassiveFailoverRouter;
import com.omnirelay.engine.model.RenderedEmailPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "omnirelay.kafka.enabled", havingValue = "true")
public class RegionalNotificationKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(RegionalNotificationKafkaListener.class);

    private final ActivePassiveFailoverRouter failoverRouter;
    private final OmniRelayProperties properties;
    private final ObjectMapper objectMapper;

    public RegionalNotificationKafkaListener(
            ActivePassiveFailoverRouter failoverRouter,
            OmniRelayProperties properties,
            ObjectMapper objectMapper) {
        this.failoverRouter = failoverRouter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = "omnirelay-regional-consumer",
            topics = {
                    "omnirelay.notifications.us-east.v1",
                    "omnirelay.notifications.eu.v1",
                    "omnirelay.notifications.ap-northeast.v1",
                    "omnirelay.notifications.us-gov.v1"
            },
            groupId = "omnirelay-delivery-workers")
    public void onRegionalMessage(String payloadJson) {
        if (!properties.getKafka().isAsyncDispatch()) {
            return;
        }
        try {
            RenderedEmailPayload payload = objectMapper.readValue(payloadJson, RenderedEmailPayload.class);
            failoverRouter.dispatch(payload);
        } catch (Exception ex) {
            log.error("Failed to process regional Kafka notification payload: {}", ex.getMessage(), ex);
        }
    }
}
