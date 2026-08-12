#!/usr/bin/env bash
#
# Returns the public demo to a known baseline. Run nightly by reset-demo.timer.
#
# Without this the demo drifts: every visitor who presses "Run the comparison" seeds more
# orders, the tenant creeps toward its cap, and JOIN_FETCH gets slower until the first
# impression of the demo is a page that appears to hang. A demo that degrades the more it is
# used is worse than no demo.
#
# Truncates rather than deleting per-tenant so the sequences reset too, which keeps the
# generated order references short and readable.

set -euo pipefail

STACK_DIR="${STACK_DIR:-/opt/spring-microservices}"
BASELINE_ORDERS="${BASELINE_ORDERS:-800}"
LINES_PER_ORDER="${LINES_PER_ORDER:-8}"
ORIGIN="${ORIGIN:-https://spring-microservices.ai-agentic-enterprises.com}"

cd "$STACK_DIR"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

echo "[$(date -Is)] resetting demo data"

$COMPOSE exec -T postgres psql -U postgres -d orders \
  -c "truncate table order_lines, orders restart identity cascade;" >/dev/null

# Stock levels are seeded by a Flyway migration, so they are restored by hand rather than by
# re-running the migration -- Flyway will not repeat a version it has already applied.
$COMPOSE exec -T postgres psql -U postgres -d inventory -c "
  update stock_items set available = case sku
    when 'SKU-MONITOR' then 120 when 'SKU-DOCK' then 80 when 'SKU-CABLE' then 1000
    when 'SKU-WEBCAM' then 200 when 'SKU-HEADSET' then 300 when 'SKU-STAND' then 150
    else 500 end;" >/dev/null

# Redis holds stock levels keyed per tenant; leaving it populated would serve the pre-reset
# numbers until the TTL expires.
$COMPOSE exec -T redis redis-cli FLUSHALL >/dev/null

TOKEN=$(curl -sS -m 30 -X POST "${ORIGIN}/realms/platform/protocol/openid-connect/token" \
  -d client_id=platform-cli -d username=alice -d password=alice -d grant_type=password \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

RESULT=$(curl -sS -m 180 -X POST -H "Authorization: Bearer ${TOKEN}" \
  "${ORIGIN}/api/admin/seed?orders=${BASELINE_ORDERS}&linesPerOrder=${LINES_PER_ORDER}")

echo "[$(date -Is)] reseeded: ${RESULT}"
