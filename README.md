# OmniRelay Notification & Webhook Delivery Engine

> **High-throughput, multi-region, multi-provider notification and webhook delivery engine with automated active-passive failover (AWS SESv2 / SendGrid), OAuth2 webhook signing, and data-residency routing.**

[![Java 21](https://img.shields.io/badge/Java-21%20Virtual%20Threads-orange.svg)](#)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](#)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-Circuit%20Breaker-blue.svg)](#)

---

## Architecture Overview

1. **Active-Passive Failover Router (`Java 21 Virtual Threads + Resilience4j + Redis`)**
   - Monitors `SENDGRID` and `AWS_SES_V2` error rates and slow-call latencies over configurable sliding windows (`ResilienceConfig`, `ActivePassiveFailoverRouter`).
   - Automatically trips from `SENDGRID` to `AWS_SES_V2` (or vice versa) in `< 30 seconds` (sub-second reaction on circuit open/rate breach) and syncs regional routing state in Redis (`ProviderRoutingStateService`).
   - In-flight messages fail over to the passive provider within the same virtual thread; any dual-provider outage is persisted to `ZeroLossOutboxService` + regional DLQ for **0 lost messages**.

2. **Deliverability, Sanitization & Suppression Pipeline**
   - **Real-time Suppression Webhooks** (`DeliverabilityWebhookController`): Ingests SendGrid (`bounce`, `dropped`, `spamreport`, `unsubscribe`) and AWS SES / SNS (`Bounce`, `Complaint`, `Reject`) webhooks and updates an L1 Caffeine + L2 Redis suppression tier (`SuppressionListService`).
   - **HTML-Injection Sanitization** (`HtmlSanitizerService`): Uses Jsoup with a transactional email `Safelist` to strip `<script>`, `<iframe>`, `<object>`, `javascript:`, and `on*` XSS vectors from both raw HTML and template variables.
   - **Two-Tier Template Caching** (`TemplateCacheService`): L1 Caffeine + L2 Redis template cache with placeholder compilation and automatic sanitization.
   - **HashiCorp Vault Secret Rotation** (`VaultSecretRotationService`): Dual-version secret rotation (`currentSecret` + `previousSecret` grace window) for SendGrid API keys, AWS SES SigV4 credentials, webhook HMAC keys, and OAuth 2.0 client secrets.

3. **Data Residency Policy Router & Enterprise Webhooks**
   - **Policy Routing** (`DataResidencyPolicyRouter`): Pins regulated tenant payloads to regional Kafka topics and endpoints:
     - `EU` -> `omnirelay.notifications.eu.v1` (`eu-central-1`)
     - `AP_NORTHEAST` -> `omnirelay.notifications.ap-northeast.v1` (`ap-northeast-1`)
     - `US_GOV` -> `omnirelay.notifications.us-gov.v1` (`us-gov-west-1`, strictly pinned to `AWS_SES_V2`)
     - `US_EAST` -> `omnirelay.notifications.us-east.v1` (`us-east-1`)
   - **Enterprise Webhooks** (`EnterpriseWebhookDeliveryService`, `OAuth2ClientCredentialsService`): Acquires cached OAuth 2.0 `client_credentials` Bearer tokens, signs payloads with `X-OmniRelay-Signature-256: t=<epoch>,v1=<hmac_sha256>`, and validates/attaches SHA-256 verified rich attachments.

4. **"Chaos Gameday in a Box" (`gameday/`)**
   - Full Docker Compose stack (`kafka`, `redis`, `vault`, `toxiproxy`, `mock-upstreams`, `prometheus`, `grafana`, `omnirelay-engine`).
   - `gameday/k6-chaos-loadtest.js`: 1,000 req/sec arrival-rate load test asserting `dropped_messages == 0` and `MTTR < 30s`.
   - `gameday/inject-sendgrid-blackout.sh`: Injects a SendGrid blackout via Toxiproxy API and validates automatic failover.
   - `gameday/capture-grafana-proof.py`: Captures live Prometheus/engine metrics and renders `gameday/artifacts/grafana-failover-proof.png`.

---

## Quick Start

### 1. Run Full Test Suite
```bash
mvn clean test
```

### 2. Run Chaos Gameday in a Box
```bash
bash gameday/run-gameday.sh
```
Outputs:
- `gameday/artifacts/grafana-failover-proof.png`
- `gameday/artifacts/gameday-summary.json`

### 3. Docker Compose Full Stack (Kafka + Redis + Vault + Toxiproxy + Prometheus + Grafana)
```bash
docker compose up -d --build
# Grafana UI: http://localhost:3000 (admin / omnirelay)
# Prometheus UI: http://localhost:9090
```

---

## Key API Endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/notifications/send` | Route, sanitize, suppress-check, and deliver notification |
| `POST` | `/api/v1/webhooks/outbound/send` | Deliver OAuth2 + HMAC-SHA256 signed webhook with attachments |
| `POST` | `/api/v1/webhooks/inbound/sendgrid` | Real-time SendGrid bounce/complaint/unsubscribe ingestion |
| `POST` | `/api/v1/webhooks/inbound/ses` | Real-time AWS SES / SNS bounce/complaint ingestion |
| `POST` | `/api/v1/templates` | Register/update cached email template |
| `GET` | `/api/v1/failover/status` | Live circuit breaker states, active regional providers & MTTR history |
| `POST` | `/api/v1/failover/switch` | Manual operator/gameday failover switch |
| `POST` | `/api/v1/secrets/rotate` | Trigger zero-downtime Vault secret rotation |
| `GET` | `/api/v1/metrics/summary` | Delivery, failover, suppression, and zero-loss summary |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint |
