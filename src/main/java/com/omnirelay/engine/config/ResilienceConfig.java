package com.omnirelay.engine.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    public static final String SENDGRID_CB = "sendgrid-cb";
    public static final String AWS_SES_V2_CB = "aws-ses-v2-cb";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(OmniRelayProperties properties, MeterRegistry meterRegistry) {
        OmniRelayProperties.Failover failover = properties.getFailover();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(failover.getSlidingWindowSize())
                .minimumNumberOfCalls(failover.getMinimumNumberOfCalls())
                .failureRateThreshold(failover.getFailureRateThreshold())
                .slowCallRateThreshold(failover.getSlowCallRateThreshold())
                .slowCallDurationThreshold(failover.getSlowCallDurationThreshold())
                .waitDurationInOpenState(failover.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(failover.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        registry.circuitBreaker(SENDGRID_CB, config);
        registry.circuitBreaker(AWS_SES_V2_CB, config);

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
