package com.omnirelay.engine.webhook;

public class WebhookDeliveryException extends RuntimeException {

    private final int statusCode;

    public WebhookDeliveryException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public WebhookDeliveryException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
