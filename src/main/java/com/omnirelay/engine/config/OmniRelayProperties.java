package com.omnirelay.engine.config;

import com.omnirelay.engine.provider.ProviderType;
import com.omnirelay.engine.residency.ResidencyRegion;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@ConfigurationProperties(prefix = "omnirelay")
public class OmniRelayProperties {

    private Failover failover = new Failover();
    private Providers providers = new Providers();
    private Kafka kafka = new Kafka();
    private Redis redis = new Redis();
    private Vault vault = new Vault();
    private Webhook webhook = new Webhook();
    private Residency residency = new Residency();

    public Failover getFailover() {
        return failover;
    }

    public void setFailover(Failover failover) {
        this.failover = failover;
    }

    public Providers getProviders() {
        return providers;
    }

    public void setProviders(Providers providers) {
        this.providers = providers;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Vault getVault() {
        return vault;
    }

    public void setVault(Vault vault) {
        this.vault = vault;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    public Residency getResidency() {
        return residency;
    }

    public void setResidency(Residency residency) {
        this.residency = residency;
    }

    public static class Failover {
        private ProviderType defaultPrimary = ProviderType.SENDGRID;
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 5;
        private float failureRateThreshold = 50.0f;
        private float slowCallRateThreshold = 60.0f;
        private Duration slowCallDurationThreshold = Duration.ofMillis(1500);
        private Duration waitDurationInOpenState = Duration.ofSeconds(15);
        private int permittedNumberOfCallsInHalfOpenState = 5;
        private boolean automaticFailoverEnabled = true;
        private Duration targetMaxMttr = Duration.ofSeconds(30);

        public ProviderType getDefaultPrimary() {
            return defaultPrimary;
        }

        public void setDefaultPrimary(ProviderType defaultPrimary) {
            this.defaultPrimary = defaultPrimary;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public Duration getSlowCallDurationThreshold() {
            return slowCallDurationThreshold;
        }

        public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
            this.slowCallDurationThreshold = slowCallDurationThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public boolean isAutomaticFailoverEnabled() {
            return automaticFailoverEnabled;
        }

        public void setAutomaticFailoverEnabled(boolean automaticFailoverEnabled) {
            this.automaticFailoverEnabled = automaticFailoverEnabled;
        }

        public Duration getTargetMaxMttr() {
            return targetMaxMttr;
        }

        public void setTargetMaxMttr(Duration targetMaxMttr) {
            this.targetMaxMttr = targetMaxMttr;
        }
    }

    public static class Providers {
        private Duration connectTimeout = Duration.ofMillis(800);
        private Duration requestTimeout = Duration.ofMillis(2000);
        private boolean simulationMode = false;
        private Map<ResidencyRegion, String> sendgridEndpoints = defaultSendGridEndpoints();
        private Map<ResidencyRegion, String> sesEndpoints = defaultSesEndpoints();

        private static Map<ResidencyRegion, String> defaultSendGridEndpoints() {
            Map<ResidencyRegion, String> map = new EnumMap<>(ResidencyRegion.class);
            map.put(ResidencyRegion.US_EAST, "http://localhost:18081");
            map.put(ResidencyRegion.EU, "http://localhost:18081");
            map.put(ResidencyRegion.AP_NORTHEAST, "http://localhost:18081");
            map.put(ResidencyRegion.US_GOV, "http://localhost:18081");
            return map;
        }

        private static Map<ResidencyRegion, String> defaultSesEndpoints() {
            Map<ResidencyRegion, String> map = new EnumMap<>(ResidencyRegion.class);
            map.put(ResidencyRegion.US_EAST, "http://localhost:18082");
            map.put(ResidencyRegion.EU, "http://localhost:18082");
            map.put(ResidencyRegion.AP_NORTHEAST, "http://localhost:18082");
            map.put(ResidencyRegion.US_GOV, "http://localhost:18082");
            return map;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public boolean isSimulationMode() {
            return simulationMode;
        }

        public void setSimulationMode(boolean simulationMode) {
            this.simulationMode = simulationMode;
        }

        public Map<ResidencyRegion, String> getSendgridEndpoints() {
            return sendgridEndpoints;
        }

        public void setSendgridEndpoints(Map<ResidencyRegion, String> sendgridEndpoints) {
            this.sendgridEndpoints = sendgridEndpoints;
        }

        public Map<ResidencyRegion, String> getSesEndpoints() {
            return sesEndpoints;
        }

        public void setSesEndpoints(Map<ResidencyRegion, String> sesEndpoints) {
            this.sesEndpoints = sesEndpoints;
        }
    }

    public static class Kafka {
        private boolean enabled = false;
        private boolean asyncDispatch = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAsyncDispatch() {
            return asyncDispatch;
        }

        public void setAsyncDispatch(boolean asyncDispatch) {
            this.asyncDispatch = asyncDispatch;
        }
    }

    public static class Redis {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Vault {
        private boolean enabled = false;
        private String uri = "http://localhost:8200";
        private String token = "omnirelay-dev-root-token";
        private String kvMount = "secret";
        private String secretPath = "omnirelay/runtime";
        private Duration rotationInterval = Duration.ofHours(6);
        private Duration graceWindow = Duration.ofMinutes(15);
        private String initialSendgridApiKey = "SG.omnirelay-initial-v1-key";
        private String initialAwsAccessKeyId = "AKIAOMNIRELAYV1";
        private String initialAwsSecretAccessKey = "omnirelay-aws-ses-secret-v1";
        private String initialWebhookHmacKey = "whsec_omnirelay_hmac_v1";
        private String initialOauth2ClientSecret = "oauth2_omnirelay_secret_v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getKvMount() {
            return kvMount;
        }

        public void setKvMount(String kvMount) {
            this.kvMount = kvMount;
        }

        public String getSecretPath() {
            return secretPath;
        }

        public void setSecretPath(String secretPath) {
            this.secretPath = secretPath;
        }

        public Duration getRotationInterval() {
            return rotationInterval;
        }

        public void setRotationInterval(Duration rotationInterval) {
            this.rotationInterval = rotationInterval;
        }

        public Duration getGraceWindow() {
            return graceWindow;
        }

        public void setGraceWindow(Duration graceWindow) {
            this.graceWindow = graceWindow;
        }

        public String getInitialSendgridApiKey() {
            return initialSendgridApiKey;
        }

        public void setInitialSendgridApiKey(String initialSendgridApiKey) {
            this.initialSendgridApiKey = initialSendgridApiKey;
        }

        public String getInitialAwsAccessKeyId() {
            return initialAwsAccessKeyId;
        }

        public void setInitialAwsAccessKeyId(String initialAwsAccessKeyId) {
            this.initialAwsAccessKeyId = initialAwsAccessKeyId;
        }

        public String getInitialAwsSecretAccessKey() {
            return initialAwsSecretAccessKey;
        }

        public void setInitialAwsSecretAccessKey(String initialAwsSecretAccessKey) {
            this.initialAwsSecretAccessKey = initialAwsSecretAccessKey;
        }

        public String getInitialWebhookHmacKey() {
            return initialWebhookHmacKey;
        }

        public void setInitialWebhookHmacKey(String initialWebhookHmacKey) {
            this.initialWebhookHmacKey = initialWebhookHmacKey;
        }

        public String getInitialOauth2ClientSecret() {
            return initialOauth2ClientSecret;
        }

        public void setInitialOauth2ClientSecret(String initialOauth2ClientSecret) {
            this.initialOauth2ClientSecret = initialOauth2ClientSecret;
        }
    }

    public static class Webhook {
        private Duration requestTimeout = Duration.ofSeconds(3);
        private long maxAttachmentBytes = 5 * 1024 * 1024;
        private boolean verifyInboundSignature = false;

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public long getMaxAttachmentBytes() {
            return maxAttachmentBytes;
        }

        public void setMaxAttachmentBytes(long maxAttachmentBytes) {
            this.maxAttachmentBytes = maxAttachmentBytes;
        }

        public boolean isVerifyInboundSignature() {
            return verifyInboundSignature;
        }

        public void setVerifyInboundSignature(boolean verifyInboundSignature) {
            this.verifyInboundSignature = verifyInboundSignature;
        }
    }

    public static class Residency {
        private boolean strictGovCloudPinning = true;
        private boolean enforceRegionalIsolation = true;

        public boolean isStrictGovCloudPinning() {
            return strictGovCloudPinning;
        }

        public void setStrictGovCloudPinning(boolean strictGovCloudPinning) {
            this.strictGovCloudPinning = strictGovCloudPinning;
        }

        public boolean isEnforceRegionalIsolation() {
            return enforceRegionalIsolation;
        }

        public void setEnforceRegionalIsolation(boolean enforceRegionalIsolation) {
            this.enforceRegionalIsolation = enforceRegionalIsolation;
        }
    }
}
