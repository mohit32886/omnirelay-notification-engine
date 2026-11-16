package com.omnirelay.engine.residency;

public class ResidencyPolicyViolationException extends RuntimeException {

    public ResidencyPolicyViolationException(String message) {
        super(message);
    }
}
