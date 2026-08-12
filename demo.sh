#!/usr/bin/env bash
#
# End-to-end demonstration against the running stack.
#
#   docker compose up -d --build     # or: make up
#   ./demo.sh
#
# Every number this prints is measured at the moment it runs. Nothing is hard-coded.

set -euo pipefail

KEYCLOAK_PORT="${KEYCLOAK_PORT:-18180}"
ORDERS_PORT="${ORDERS_PORT:-8081}"
INVENTORY_PORT="${INVENTORY_PORT:-8082}"
GATEWAY_PORT="${GATEWAY_PORT:-18080}"

KEYCLOAK="http://localhost:${KEYCLOAK_PORT}"
ORDERS="http://localhost:${ORDERS_PORT}"
INVENTORY="http://localhost:${INVENTORY_PORT}"
GATEWAY="http://localhost:${GATEWAY_PORT}"

SEED_ORDERS="${SEED_ORDERS:-200}"
SEED_LINES="${SEED_LINES:-8}"
PAGE_SIZE="${PAGE_SIZE:-20}"

bold()    { printf '\033[1m%s\033[0m\n' "$*"; }
heading() { printf '\n\033[1;36m%s\033[0m\n' "$*"; printf '%*s\n' "${#1}" '' | tr ' ' '-'; }
ok()      { printf '  \033[32m✓\033[0m %s\n' "$*"; }
fail()    { printf '  \033[31m✗\033[0m %s\n' "$*"; exit 1; }

jqp() { python3 -c "import sys,json;d=json.load(sys.stdin);$1"; }

token() {
  curl -sS -X POST "${KEYCLOAK}/realms/platform/protocol/openid-connect/token" \
    -d "client_id=platform-cli" -d "username=$1" -d "password=$1" \
    -d "grant_type=password" | jqp "print(d['access_token'])"
}

claims() {
  python3 - "$1" <<'PY'
import sys, json, base64
part = sys.argv[1].split('.')[1]
part += '=' * (-len(part) % 4)
c = json.loads(base64.urlsafe_b64decode(part))
print(json.dumps({
    'iss': c.get('iss'),
    'preferred_username': c.get('preferred_username'),
    'tenant_id': c.get('tenant_id'),
    'realm_roles': c.get('realm_access', {}).get('roles'),
    'client_roles': c.get('resource_access', {}).get('orders-api', {}).get('roles'),
}, indent=2))
PY
}

# ---------------------------------------------------------------------------
heading "1. Keycloak issues tenant-scoped tokens"

ALICE=$(token alice)   # tenant-a, platform-admin, orders:read + orders:write
BOB=$(token bob)       # tenant-b, same roles, different tenant
READONLY=$(token readonly)  # tenant-a, orders:read only

echo "alice's access token claims:"
claims "$ALICE" | sed 's/^/    /'
ok "the tenant is a signed claim, not something the caller supplies"

# ---------------------------------------------------------------------------
heading "2. The tenant cannot be forged"

FORGED=$(curl -sS -H "Authorization: Bearer ${ALICE}" -H "X-Tenant-Id: tenant-b" \
  "${GATEWAY}/api/orders/whoami" | jqp "print(d['tenantId'])")
[[ "$FORGED" == "tenant-a" ]] \
  && ok "sent X-Tenant-Id: tenant-b with alice's token, still resolved ${FORGED}" \
  || fail "tenant was overridden by a request header: ${FORGED}"

UNAUTH=$(curl -sS -o /dev/null -w '%{http_code}' "${GATEWAY}/api/orders")
[[ "$UNAUTH" == "401" ]] \
  && ok "unauthenticated request rejected at the gateway (HTTP 401)" \
  || fail "expected 401, got ${UNAUTH}"

FORBIDDEN=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer ${READONLY}" -H 'Content-Type: application/json' \
  -d '{"customerName":"Nope","lines":[{"sku":"SKU-DOCK","quantity":1,"unitPrice":1.00}]}' \
  "${ORDERS}/api/orders")
[[ "$FORBIDDEN" == "403" ]] \
  && ok "orders:read alone cannot POST an order (HTTP 403)" \
  || fail "expected 403 for the read-only user, got ${FORBIDDEN}"

# ---------------------------------------------------------------------------
heading "3. Seeding ${SEED_ORDERS} orders x ${SEED_LINES} lines into tenant-a"

curl -sS -X POST -H "Authorization: Bearer ${ALICE}" \
  "${ORDERS}/api/admin/seed?orders=${SEED_ORDERS}&linesPerOrder=${SEED_LINES}" \
  | jqp "print('    wrote %d orders / %d lines into %s'%(d['ordersWritten'],d['linesWritten'],d['tenantId']))"
ok "seeded"

# ---------------------------------------------------------------------------
heading "4. The same page, three fetch strategies"

printf '    %-12s %10s %10s %8s %10s\n' STRATEGY STATEMENTS ROWS MS RETURNED
printf '    %-12s %10s %10s %8s %10s\n' "------------" "----------" "----------" "--------" "----------"
for strategy in NAIVE JOIN_FETCH TWO_QUERY; do
  curl -sS -H "Authorization: Bearer ${ALICE}" \
    "${ORDERS}/api/orders?strategy=${strategy}&page=0&size=${PAGE_SIZE}" \
    | jqp "print('    %-12s %10d %10d %8d %10d'%(d['strategy'],d['jdbcStatements'],d['rowsMaterialised'],d['elapsedMillis'],d['returnedElements']))"
done
cat <<'EOF'

    NAIVE      one SELECT per order in the page, on top of the page query itself.
    JOIN_FETCH two statements -- and every row in the table materialised, because
               Hibernate cannot LIMIT a joined collection and paginates in memory.
    TWO_QUERY  id page in the database, then one fetch for those ids. Flat.
EOF

# ---------------------------------------------------------------------------
heading "5. Order placement across Kafka"

BEFORE=$(curl -sS -H "Authorization: Bearer ${ALICE}" "${INVENTORY}/api/inventory/SKU-DOCK" | jqp "print(d['available'])")
echo "    SKU-DOCK before:            ${BEFORE}"

PLACED=$(curl -sS -X POST -H "Authorization: Bearer ${ALICE}" -H 'Content-Type: application/json' \
  -d '{"customerName":"Acme Corp","lines":[{"sku":"SKU-DOCK","quantity":3,"unitPrice":149.00}]}' \
  "${ORDERS}/api/orders")
REF=$(echo "$PLACED" | jqp "print(d['orderRef'])")
echo "    placed ${REF}, status $(echo "$PLACED" | jqp "print(d['status'])")"

echo -n "    waiting for the round trip"
for _ in $(seq 1 20); do
  sleep 1; echo -n "."
  STATUS=$(curl -sS -H "Authorization: Bearer ${ALICE}" "${ORDERS}/api/orders?size=100" \
    | jqp "print(next((o['status'] for o in d['content'] if o['orderRef']=='${REF}'),'?'))")
  [[ "$STATUS" != "PLACED" ]] && break
done
echo

AFTER=$(curl -sS -H "Authorization: Bearer ${ALICE}" "${INVENTORY}/api/inventory/SKU-DOCK" | jqp "print(d['available'])")
echo "    SKU-DOCK after:             ${AFTER}"
echo "    ${REF} status:              ${STATUS}"

[[ "$STATUS" == "RESERVED" ]] \
  && ok "orders.placed -> inventory reserved -> inventory.stock-reserved -> order RESERVED" \
  || fail "order did not reach RESERVED (got ${STATUS})"

[[ "$AFTER" -eq $((BEFORE - 3)) ]] \
  && ok "the cached read reflects the write: ${BEFORE} -> ${AFTER} (Redis entry was evicted)" \
  || fail "stock did not decrement correctly: ${BEFORE} -> ${AFTER}"

# ---------------------------------------------------------------------------
heading "6. Tenant isolation"

A_COUNT=$(curl -sS -H "Authorization: Bearer ${ALICE}" "${ORDERS}/api/orders?size=1" | jqp "print(d['totalElements'])")
B_COUNT=$(curl -sS -H "Authorization: Bearer ${BOB}"   "${ORDERS}/api/orders?size=1" | jqp "print(d['totalElements'])")
B_STOCK=$(curl -sS -H "Authorization: Bearer ${BOB}"   "${INVENTORY}/api/inventory/SKU-DOCK" | jqp "print(d['available'])")

echo "    alice (tenant-a) sees ${A_COUNT} orders"
echo "    bob   (tenant-b) sees ${B_COUNT} orders"
echo "    bob's SKU-DOCK stock: ${B_STOCK} (alice's order took 3 from tenant-a only)"

[[ "$B_COUNT" -eq 0 ]] \
  && ok "the same query, the same tables, no rows from the other tenant" \
  || fail "tenant-b can see ${B_COUNT} orders it should not"

[[ "$B_STOCK" -ne "$AFTER" ]] \
  && ok "and the Redis entries are per-tenant too (stock::tenant-a:SKU-DOCK is a different key)" \
  || fail "tenant-b's stock moved with tenant-a's order"

printf '\n'
bold "All checks passed."
printf 'Swagger UI:  %s/swagger-ui.html   (Authorize -> alice / alice)\n' "${ORDERS}"
printf 'Keycloak:    %s  (admin / admin)\n' "${KEYCLOAK}"
