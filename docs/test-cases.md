# Test cases

18 automated tests across four suites, plus a 12-step end-to-end script that runs against the
real stack. This document states what each case asserts and, more usefully, **which specific
production failure it would have caught** — a test that cannot be traced to a failure it
prevents is usually a test that only asserts the code does what the code does.

Run everything: `make test` · without Docker: `make test-fast` · against a live stack: `./demo.sh`

| Suite | Cases | Needs Docker | Duration |
|---|---:|---|---:|
| `KeycloakJwtAuthenticationConverterTest` | 6 | no | ~0.3 s |
| `TenantIsolationTest` | 5 | Postgres | ~60 s |
| `FetchStrategyTest` | 4 | Postgres | ~8 s |
| `CacheTenantIsolationTest` | 3 | Postgres + Redis | ~55 s |
| `demo.sh` (end-to-end) | 12 assertions | full stack | ~90 s |

Integration tests use **real Postgres and Redis via Testcontainers, not H2**. The behaviour
under test is Hibernate's generated SQL — tenant predicates, `LIMIT` placement, how `join
fetch` interacts with pagination. An in-memory database in compatibility mode is a different
dialect wearing a costume, and it hides exactly these differences.

---

## TC-1 · Keycloak token → Spring Security authorities

No Docker required. This is where most "my roles don't work" reports actually live.

| ID | Case | Asserts | Catches |
|---|---|---|---|
| 1.1 | Realm roles map to authorities | `realm_access.roles: [platform-admin]` → `ROLE_platform-admin` | Spring's default converter reads `scope`, which Keycloak does not populate with roles. Every user arrives as `SCOPE_profile` and **every `hasRole()` check silently fails**. |
| 1.2 | Client roles map to authorities | `resource_access.orders-api.roles` → `ROLE_orders:read`, `ROLE_orders:write` | The same failure for client-scoped roles, which is where per-API permissions live. |
| 1.3 | Other clients' roles are ignored | `resource_access.billing-api.roles: [billing:admin]` → **not** granted | Keycloak puts *every* client's roles in one token. Without filtering by client id, an admin of an unrelated client silently gains that authority here. A real privilege-escalation path, not a hypothetical. |
| 1.4 | Missing claims degrade safely | Token with no role claims → empty authorities, no exception | A `NullPointerException` in the auth converter turns a permission problem into a 500 on every request. |
| 1.5 | Tenant read from configured claim | `tenant_id: tenant-a` → `"tenant-a"`; absent → `null` | Silent binding to the wrong claim name, which fails open. |
| 1.6 | Principal prefers username | `preferred_username` wins; falls back to `sub` | Audit logs full of opaque UUIDs instead of usernames. |

## TC-2 · Tenant isolation (real Postgres)

Every case writes as **two** tenants and reads as one. That is the whole point: most
multi-tenant suites exercise a single tenant, which is precisely why cross-tenant reads survive
to production — with one tenant in the database, a missing `WHERE tenant_id = ?` returns
exactly the right answer.

| ID | Case | Asserts | Catches |
|---|---|---|---|
| 2.1 | Reads scoped to bound tenant | tenant-A sees 3 orders, tenant-B sees 5 | The baseline leak: one tenant reading another's rows. |
| 2.2 | Listing never leaks | Every row returned to tenant-B is tenant-B's | A repository method that forgot its tenant predicate. |
| 2.3 | **Raw JPQL is filtered too** | tenant-A querying `orderRef like 'B-ORDER%'` → **0** | The usual way isolation is lost: a developer bypasses the repository and hand-writes a query. `@TenantId` applies at the mapping layer, so there is no escape hatch. |
| 2.4 | Unbound thread fails closed | No tenant bound → `totalElements == 0` | A background job or Kafka listener that forgot to bind a tenant running **unfiltered across every customer's data**. An empty list gets investigated; a full one gets shipped. |
| 2.5 | Explicit access throws | `TenantContext.requireTenant()` → `TenantMissingException` | Code that needs a tenant silently getting a default instead of an error. |

## TC-3 · Fetch strategy cost (real Postgres)

Asserts **statement counts**, not elapsed time. Wall-clock thresholds are flaky on shared CI
runners; "this page must cost three statements" is exact and fails for exactly one reason.

Fixture: 200 orders × 8 lines, page size 20.

| ID | Case | Asserts | Catches |
|---|---|---|---|
| 3.1 | `NAIVE` is 1+N | ≥ 20 statements for a 20-row page | The classic N+1, reintroduced by anyone who touches the mapping layer. |
| 3.2 | **`JOIN_FETCH` hides its cost** | ≤ 3 statements **and** ≥ 200 rows materialised | The trap. The "fix" that looks correct — few statements — while loading the entire table into the persistence context. Asserting only on statement count would score this as the *best* strategy. |
| 3.3 | `TWO_QUERY` is flat | ≤ 3 statements, fewer statements than `NAIVE`, fewer rows than `JOIN_FETCH` | Regression back to either failure mode. |
| 3.4 | All strategies agree | Same order refs, same line contents | **A faster query that returns different rows is not an optimisation.** Without this, an optimisation that quietly drops rows passes every performance assertion. |

## TC-4 · Cache tenancy and invalidation (real Postgres + Redis)

Both properties fail *silently* and neither reproduces against a cold local cache.

| ID | Case | Asserts | Catches |
|---|---|---|---|
| 4.1 | Cache keys are tenant-scoped | `stock::tenant-a:SKU-DOCK` and `stock::tenant-b:SKU-DOCK` both exist independently | Redis sits outside Hibernate, so `@TenantId` cannot protect it. A key of `stock::SKU-DOCK` serves tenant B tenant A's numbers **on a hit** — invisible to any review of the repository layer. |
| 4.2 | Write evicts, and only its own tenant | After A reserves 5: A reads `before-5`, B unchanged | A missing `@CacheEvict`, or an eviction key that does not match the read key — the evict deletes a key that was never written, nothing errors, and the stale value survives the TTL. |
| 4.3 | Refused reservation changes nothing | Over-large reservation → `false`, stock unchanged | A partial write on a rejected reservation, which drifts inventory upward over time. |

## TC-5 · End-to-end (`demo.sh`, full stack)

Exits non-zero on any failed assertion, which is what makes it usable as a pipeline gate rather
than a screenshot.

| ID | Case | Asserts |
|---|---|---|
| 5.1 | Keycloak issues tenant-scoped tokens | `tenant_id`, realm roles and client roles present in a signed token |
| 5.2 | **Tenant cannot be forged** | `X-Tenant-Id: tenant-b` sent with tenant-a's token → still resolves `tenant-a` |
| 5.3 | Unauthenticated rejected at the edge | `GET /api/orders` with no token → 401 |
| 5.4 | Read-only role cannot write | `readonly` user POSTing an order → 403 |
| 5.5 | Seeding works and is bounded | Requested rows written, capped per request and per tenant |
| 5.6–5.8 | Three strategies measured | Statements, rows materialised and elapsed time reported per strategy |
| 5.9 | Kafka round trip completes | Order → `orders.placed` → reserved → `inventory.stock-reserved` → status `RESERVED` |
| 5.10 | Cache reflects the write | Stock read after reservation shows the decrement, proving eviction |
| 5.11 | Tenant isolation across services | tenant-B sees 0 orders and its own untouched stock |
| 5.12 | All assertions pass | Non-zero exit on any failure |

---

## Verified on the live deployment

Run against <https://spring-microservices.ai-agentic-enterprises.com> at an 800-order baseline:

```
STRATEGY     STATEMENTS   ROWS   MS   RETURNED
NAIVE                22    200  188         20
JOIN_FETCH            2  8,000  363         20
TWO_QUERY             3    200  107         20
```

Order `ORD-AF5C865A` placed → stock 80 → 78 → status `RESERVED`.
`readonly` POST → 403. tenant-B → 0 orders. Forged `X-Tenant-Id` → still `tenant-a`.
60 parallel requests → 9 × 200, 51 × 429 from the demo rate limiter.

## Gaps, stated rather than implied

- **No test for the saga gap.** A rejected order can still have consumed stock from earlier
  lines. That is a known, documented design limitation, not a defect a test would catch — and
  writing a test that asserts the current wrong behaviour would entrench it.
- **No load or soak testing.** Statement counts bound the algorithmic cost; they say nothing
  about behaviour at sustained concurrency.
- **Keycloak itself is not integration-tested.** The token mapping is unit-tested against
  constructed JWTs, and the real realm is exercised only by `demo.sh`. A Keycloak
  Testcontainer would close this, at roughly 40 s per suite.
- **No contract tests between producer and consumer.** The event records are hand-copied, so
  nothing fails at build time if they drift. At more than two consumers this needs a schema
  registry with compatibility enforced in CI.
