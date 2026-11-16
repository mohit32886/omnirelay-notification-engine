package com.omnirelay.engine.provider;

public enum ProviderType {
    SENDGRID("sendgrid-cb"),
    AWS_SES_V2("aws-ses-v2-cb");

    private final String circuitBreakerName;

    ProviderType(String circuitBreakerName) {
        this.circuitBreakerName = circuitBreakerName;
    }

    public String circuitBreakerName() {
        return circuitBreakerName;
    }

    public ProviderType opposite() {
        return this == SENDGRID ? AWS_SES_V2 : SENDGRID;
    }
}
