// Load test for the tracking service.
//
//   k6 run -e COURIERS=1000 -e PING_MS=1000 -e ORDER_RATE=10 loadtest/tracking.js
//
// Three things happen at once, like a real evening rush:
//   couriers  - every courier keeps a WebSocket open and sends its position every PING_MS
//   orders    - new orders arrive at ORDER_RATE/s; each one is assigned by the KNN query
//   (inside orders) the "customer" opens /ws/tracking and we measure how long a courier ping
//               takes to reach them, then the courier picks up and delivers so it's free again
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { WebSocket } from 'k6/websockets';
import { setInterval, setTimeout, clearInterval } from 'k6/timers';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');
const API_KEY = __ENV.API_KEY || 'local-dev-api-key';
const COURIERS = parseInt(__ENV.COURIERS || '500');
const PING_MS = parseInt(__ENV.PING_MS || '1000');
const ORDER_RATE = parseInt(__ENV.ORDER_RATE || '10');
const DURATION = __ENV.DURATION || '2m';
const TRACK_SECONDS = 6;

// Couriers wander around Miraflores / San Isidro, Lima
const CENTER = { lat: -12.11, lng: -77.03 };
const SPREAD = 0.025; // ~2.7 km

const pingsSent = new Counter('pings_sent');
const pingErrors = new Counter('ping_errors');
const e2eLatency = new Trend('ws_e2e_latency', true);
const assigned = new Rate('order_assigned');
const ordersCreated = new Counter('orders_created');
const assignLatency = new Trend('order_create_latency', true);

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    couriers: {
      executor: 'per-vu-iterations',
      vus: COURIERS,
      iterations: 1,
      maxDuration: '30m',
      exec: 'courier',
    },
    orders: {
      executor: 'constant-arrival-rate',
      rate: ORDER_RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: '15s', // let couriers connect and report a first position
      preAllocatedVUs: ORDER_RATE * (TRACK_SECONDS + 4),
      maxVUs: ORDER_RATE * (TRACK_SECONDS + 10),
      exec: 'order',
    },
  },
  thresholds: {
    'http_req_duration{name:create_order}': ['p(95)<300'],
    ws_e2e_latency: ['p(95)<250'],
    http_req_failed: ['rate<0.01'],
  },
};

const jitter = () => (Math.random() - 0.5) * 2 * SPREAD;
const headers = { 'Content-Type': 'application/json', 'X-API-Key': API_KEY };

export function setup() {
  const couriers = [];
  for (let start = 0; start < COURIERS; start += 50) {
    const batch = [];
    for (let i = start; i < Math.min(start + 50, COURIERS); i++) {
      batch.push(['POST', `${BASE}/api/couriers`, JSON.stringify({ name: `k6-${i}` }), { headers }]);
    }
    for (const res of http.batch(batch)) {
      const body = res.json();
      couriers.push({ id: body.id, token: body.token });
    }
  }
  const avail = couriers.map((c) => [
    'PUT',
    `${BASE}/api/me/status`,
    JSON.stringify({ status: 'AVAILABLE' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${c.token}` } },
  ]);
  for (let i = 0; i < avail.length; i += 100) http.batch(avail.slice(i, i + 100));
  const tokens = {};
  couriers.forEach((c) => (tokens[c.id] = c.token));
  return { couriers, tokens };
}

export function courier(data) {
  const me = data.couriers[exec.scenario.iterationInTest % data.couriers.length];
  // Total test time: warm-up + order phase + a little tail
  const lifetimeMs = courierLifetimeMs();
  let pos = { lat: CENTER.lat + jitter(), lng: CENTER.lng + jitter() };

  const ws = new WebSocket(`${WS_BASE}/ws/couriers?token=${me.token}`);
  ws.onopen = () => {
    // Spread the first ping so 1000 couriers don't fire in the same millisecond
    setTimeout(() => {
      const timer = setInterval(() => {
        pos = { lat: pos.lat + (Math.random() - 0.5) * 0.0004, lng: pos.lng + (Math.random() - 0.5) * 0.0004 };
        ws.send(JSON.stringify({ lat: pos.lat, lng: pos.lng, ts: Date.now() }));
        pingsSent.add(1);
      }, PING_MS);
      setTimeout(() => {
        clearInterval(timer);
        ws.close();
      }, lifetimeMs);
    }, Math.random() * PING_MS);
  };
  ws.onmessage = () => pingErrors.add(1); // the server only talks back to report a rejected ping
  ws.onerror = () => pingErrors.add(1);
}

export function order(data) {
  const pickup = { lat: CENTER.lat + jitter(), lng: CENTER.lng + jitter() };
  const dropoff = { lat: CENTER.lat + jitter(), lng: CENTER.lng + jitter() };
  const res = http.post(`${BASE}/api/orders`, JSON.stringify({ pickup, dropoff }), {
    headers,
    tags: { name: 'create_order' },
  });
  check(res, { 'order created': (r) => r.status === 201 });
  assignLatency.add(res.timings.duration);
  if (res.status !== 201) return;
  ordersCreated.add(1);
  const body = res.json();
  const isAssigned = body.status === 'ASSIGNED';
  assigned.add(isAssigned);
  if (!isAssigned) return;

  const ws = new WebSocket(`${WS_BASE}/ws/tracking?orderId=${body.id}`);
  let snapshotSeen = false;
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type !== 'location') return;
    // The first location is the on-connect snapshot (last known position, up to PING_MS old),
    // not a live push, so it doesn't count
    if (!snapshotSeen) {
      snapshotSeen = true;
      return;
    }
    // Courier pings carry the device time in "at"; both ends run on this machine's clock
    {
      const lag = Date.now() - Date.parse(msg.at);
      if (lag >= 0 && lag < 10000) e2eLatency.add(lag);
    }
  };
  ws.onopen = () => {
    setTimeout(() => {
      ws.close();
      const auth = {
        headers: { Authorization: `Bearer ${data.tokens[body.courierId]}` },
      };
      http.post(`${BASE}/api/me/orders/${body.id}/pickup`, null, { ...auth, tags: { name: 'pickup' } });
      http.post(`${BASE}/api/me/orders/${body.id}/deliver`, null, { ...auth, tags: { name: 'deliver' } });
    }, TRACK_SECONDS * 1000);
  };
}

function courierLifetimeMs() {
  return 15000 + parseDuration(DURATION) + 5000;
}

function parseDuration(d) {
  const m = /^(\d+)(ms|s|m)$/.exec(d);
  if (!m) return 120000;
  return parseInt(m[1]) * { ms: 1, s: 1000, m: 60000 }[m[2]];
}

export function handleSummary(data) {
  const v = (name, stat) => {
    const metric = data.metrics[name];
    return metric && metric.values[stat] !== undefined ? metric.values[stat] : null;
  };
  const summary = {
    couriers: COURIERS,
    pingIntervalMs: PING_MS,
    orderRatePerSec: ORDER_RATE,
    pingsSent: v('pings_sent', 'count'),
    // k6's own rate divides by the whole run (setup included); this is per second of activity
    pingsPerSec: v('pings_sent', 'count') / (courierLifetimeMs() / 1000),
    pingErrors: v('ping_errors', 'count') || 0,
    e2eP50: v('ws_e2e_latency', 'med'),
    e2eP95: v('ws_e2e_latency', 'p(95)'),
    e2eP99: v('ws_e2e_latency', 'p(99)'),
    createOrderP50: v('order_create_latency', 'med'),
    createOrderP95: v('order_create_latency', 'p(95)'),
    createOrderP99: v('order_create_latency', 'p(99)'),
    ordersPerSec: (v('orders_created', 'count') || 0) / (parseDuration(DURATION) / 1000),
    assignedRate: v('order_assigned', 'rate'),
    httpFailed: v('http_req_failed', 'rate'),
  };
  const file = `loadtest/results/summary-${COURIERS}c-${PING_MS}ms.json`;
  return {
    stdout: JSON.stringify(summary, null, 2) + '\n',
    [file]: JSON.stringify(summary, null, 2),
  };
}
