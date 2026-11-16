package com.omnirelay.engine.provider;

import com.omnirelay.engine.residency.ResidencyRegion;

public record ProviderDeliveryResponse(
        ProviderType provider,
        ResidencyRegion region,
        String providerMessageId,
        int httpStatus,
        long latencyMs,
        String endpointUsed
) {
}
