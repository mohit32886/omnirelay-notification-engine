package com.omnirelay.engine.provider;

public class ProviderDeliveryException extends RuntimeException {

    private final ProviderType provider;
    private final int statusCode;

    public ProviderDeliveryException(ProviderType provider, int statusCode, String message) {
        super(message);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public ProviderDeliveryException(ProviderType provider, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public ProviderType getProvider() {
        return provider;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
