package com.omnirelay.engine.residency;

import com.omnirelay.engine.provider.ProviderType;

import java.util.EnumSet;
import java.util.Set;

public enum ResidencyRegion {
    US_EAST(
            "us-east",
            "us-east-1",
            "omnirelay.notifications.us-east.v1",
            "omnirelay.notifications.us-east.dlq.v1",
            EnumSet.of(ProviderType.SENDGRID, ProviderType.AWS_SES_V2),
            false
    ),
    EU(
            "eu",
            "eu-central-1",
            "omnirelay.notifications.eu.v1",
            "omnirelay.notifications.eu.dlq.v1",
            EnumSet.of(ProviderType.SENDGRID, ProviderType.AWS_SES_V2),
            false
    ),
    AP_NORTHEAST(
            "ap-northeast",
            "ap-northeast-1",
            "omnirelay.notifications.ap-northeast.v1",
            "omnirelay.notifications.ap-northeast.dlq.v1",
            EnumSet.of(ProviderType.SENDGRID, ProviderType.AWS_SES_V2),
            false
    ),
    US_GOV(
            "us-gov",
            "us-gov-west-1",
            "omnirelay.notifications.us-gov.v1",
            "omnirelay.notifications.us-gov.dlq.v1",
            EnumSet.of(ProviderType.AWS_SES_V2),
            true
    );

    private final String code;
    private final String awsRegion;
    private final String kafkaTopic;
    private final String dlqTopic;
    private final Set<ProviderType> allowedProviders;
    private final boolean strictGovCloud;

    ResidencyRegion(
            String code,
            String awsRegion,
            String kafkaTopic,
            String dlqTopic,
            Set<ProviderType> allowedProviders,
            boolean strictGovCloud) {
        this.code = code;
        this.awsRegion = awsRegion;
        this.kafkaTopic = kafkaTopic;
        this.dlqTopic = dlqTopic;
        this.allowedProviders = Set.copyOf(allowedProviders);
        this.strictGovCloud = strictGovCloud;
    }

    public String code() {
        return code;
    }

    public String awsRegion() {
        return awsRegion;
    }

    public String kafkaTopic() {
        return kafkaTopic;
    }

    public String dlqTopic() {
        return dlqTopic;
    }

    public Set<ProviderType> allowedProviders() {
        return allowedProviders;
    }

    public boolean strictGovCloud() {
        return strictGovCloud;
    }

    public boolean supportsProvider(ProviderType providerType) {
        return allowedProviders.contains(providerType);
    }
}
