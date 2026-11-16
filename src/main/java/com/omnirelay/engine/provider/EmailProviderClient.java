package com.omnirelay.engine.provider;

import com.omnirelay.engine.model.RenderedEmailPayload;
import com.omnirelay.engine.residency.ResidencyRegion;

public interface EmailProviderClient {

    ProviderType providerType();

    ProviderDeliveryResponse send(RenderedEmailPayload payload, ResidencyRegion region);
}
