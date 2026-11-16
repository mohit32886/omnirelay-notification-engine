import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

export const deliveredCount = new Counter('omnirelay_k6_delivered_total');
export const droppedMessages = new Counter('dropped_messages');
export const failoverRoutedCount = new Counter('omnirelay_k6_failover_routed_total');
export const deliveryLatency = new Trend('omnirelay_k6_delivery_latency_ms');

export const options = {
  scenarios: {
    high_throughput_chaos: {
      executor: 'constant-arrival-rate',
      rate: 1000,
      timeUnit: '1s',
      duration: __ENV.DURATION || '45s',
      preAllocatedVUs: 150,
      maxVUs: 600,
    },
  },
  thresholds: {
    dropped_messages: ['count==0'],
    http_req_failed: ['rate<0.001'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.OMNIRELAY_URL || 'http://localhost:8080';
const REGIONS = ['EU', 'AP_NORTHEAST', 'US_EAST', 'US_GOV'];

export default function () {
  const region = REGIONS[(__VU + __ITER) % REGIONS.length];
  const payload = JSON.stringify({
    tenantId: `tenant-${region.toLowerCase()}`,
    tenantRegion: region,
    recipientEmail: `user-${__VU}-${__ITER}@example.com`,
    senderEmail: 'noreply@omnirelay.io',
    subject: `[Chaos Gameday] Multi-Region Notification (${region})`,
    rawHtmlBody: '<p>Transactional payload verified via OmniRelay.</p>',
    variables: { region },
  });

  const res = http.post(`${BASE_URL}/api/v1/notifications/send`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '4s',
  });

  const ok = check(res, {
    'HTTP 200 accepted': (r) => r.status === 200,
    'Zero message loss (DELIVERED or QUEUED_ZERO_LOSS_RETRY)': (r) => {
      if (r.status !== 200) return false;
      const body = r.json();
      return body.status === 'DELIVERED' || body.status === 'QUEUED_ZERO_LOSS_RETRY';
    },
  });

  if (!ok) {
    droppedMessages.add(1);
  } else {
    deliveredCount.add(1);
    const body = res.json();
    deliveryLatency.add(res.timings.duration);
    if (body.failoverTriggered === true || body.providerUsed === 'AWS_SES_V2') {
      failoverRoutedCount.add(1);
    }
  }
}
