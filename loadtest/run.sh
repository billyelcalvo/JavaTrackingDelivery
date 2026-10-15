#!/usr/bin/env bash
# Runs the load test at several courier counts against the docker compose stack.
# Each level starts from an empty database so leftovers from a previous run don't skew it.
#   ./loadtest/run.sh            -> 500, 1000, 2000 couriers
#   LEVELS="300" ./loadtest/run.sh
set -euo pipefail

LEVELS=${LEVELS:-"500 1000 2000"}
DURATION=${DURATION:-2m}
ORDER_RATE=${ORDER_RATE:-20}
PING_MS=${PING_MS:-1000}

for couriers in $LEVELS; do
  echo ">> $couriers couriers, ping every ${PING_MS}ms, $ORDER_RATE orders/s for $DURATION"
  docker compose exec -T db psql -U "${POSTGRES_USER:-tracking}" -d "${POSTGRES_DB:-tracking}" -qc \
    "TRUNCATE delivery_order, courier_location_history, courier_location, courier CASCADE"
  docker compose restart app >/dev/null
  until curl -sf localhost:8080/actuator/health/readiness >/dev/null; do sleep 1; done
  k6 run -q -e COURIERS="$couriers" -e PING_MS="$PING_MS" -e ORDER_RATE="$ORDER_RATE" \
    -e DURATION="$DURATION" loadtest/tracking.js || true
done
