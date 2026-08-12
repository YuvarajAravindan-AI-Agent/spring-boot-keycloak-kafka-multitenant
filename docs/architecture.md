# Architecture and design decisions

Companion to the [README](../README.md). This document covers *why* each choice was made,
including the ones that are deliberately wrong for production and the ones that are wrong
everywhere but common anyway.

---

## 1. Multi-tenancy: discriminator, not schema or database

Three options, in increasing order of isolation and cost:

| Strategy | Isolation | Cost |
|---|---|---|
| Database per tenant | Strongest | A connection pool and a migration run per tenant |
| Schema per tenant | Strong | Migrations multiply; connection routing per request |
| **Discriminator column** | Weakest — one bad query away | One pool, one schema, one migration |

This implementation uses the discriminator, because it is what most B2B SaaS actually runs and
therefore where the interesting failure lives. The weakness — "one bad query away" — is exactly
what `@TenantId` removes:

```java
@TenantId
@Column(name = "tenant_id", nullable = false, updatable = false)
private String tenantId;
```

Hibernate fills this on write and appends `tenant_id = ?` on every read it generates. There is
no setter and no constructor parameter, so application code cannot choose a tenant; and because
the predicate is added at the mapping level rather than by a repository method, hand-written
JPQL is filtered too. `TenantIsolationTest` asserts that a query naming another tenant's rows
explicitly returns zero.

**Where this stops working.** Native queries (`@Query(nativeQuery = true)`) and anything going
through raw JDBC bypass Hibernate entirely, and therefore bypass the predicate. A system relying
on this needs a lint rule or a review policy for native SQL. Postgres row-level security is the
belt-and-braces answer — the database enforces it even for a psql session — at the cost of a
`SET app.tenant` on every connection checkout and noticeably harder debugging.

**Choosing where the tenant comes from.** It is a token claim (`tenant_id`), populated by a
Keycloak protocol mapper from a user attribute. The alternatives are worse:

- *A request header* — forgeable by definition. The gateway strips `X-Tenant-Id` for this reason.
- *A subdomain* — forgeable behind a proxy unless carefully pinned, and it couples routing to identity.
- *A database lookup on user id* — correct but adds a query to every request, and the lookup
  itself needs a tenant to be safe.

A signed claim is validated once, costs nothing per request, and cannot be tampered with without
the realm's private key.

## 2. Why the tenant lives in a ThreadLocal

`TenantContext` is a `ThreadLocal`, which is unfashionable and, for a servlet stack, correct:
it lets Hibernate's `CurrentTenantIdentifierResolver` — invoked deep inside session creation,
far from any controller — reach the tenant without threading a parameter through every layer.

The failure mode is real and this code guards it in two places. Servlet containers and Kafka
listener containers both pool threads, so a tenant left bound after a request is a tenant the
*next* request inherits. Both `TenantContextFilter` and `StockReservedListener` clear in a
`finally`.

**This does not survive `@Async` or a reactive pipeline.** Work handed to another thread loses
the binding, and the resolver falls back to the unbound sentinel — which returns nothing rather
than everything, so the failure is a mysterious empty list rather than a breach. That is the
right trade, and it is why `gateway-service` does not depend on `common-security` at all:
WebFlux serves many requests per event-loop thread, where this design leaks by construction.
A reactive service would carry the tenant in the Reactor `Context` instead.

## 3. Event contracts are duplicated on purpose

`OrderPlacedEvent` is declared twice — once in `orders-service`, once in `inventory-service` —
with the consumer's copy deliberately omitting the `customerName` field it does not use.

A shared `events` jar is the obvious alternative and it quietly re-couples the services: every
consumer becomes a compile-time dependency of the producer, and adding one optional field turns
into a coordinated redeploy of everything. The duplication costs a hand-maintained copy on each
side. In exchange, Jackson ignores unknown properties and the producer can add fields whenever
it likes.

This is why the consumers set `spring.json.use.type.headers: false`. Spring Kafka's JSON
serializer stamps the producer's fully-qualified class name into a header by default; the
consumer, whose copy lives in a different package, cannot resolve it and rejects the record.
Pinning `spring.json.value.default.type` tells each consumer to deserialize into *its own*
type regardless of what the producer called it.

**At a larger scale this becomes a schema registry** with Avro or Protobuf and enforced
compatibility rules. Hand-maintained copies stop being viable somewhere around the third
consumer.

## 4. Publishing after commit, and what that still does not fix

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override public void afterCommit() { send.run(); }
});
```

Publishing inside the transaction is the common shape and it races: Kafka delivers in
milliseconds, the consumer calls back for the order, and the producing transaction has not
committed yet. The bug reproduces under load and never on a developer's machine.

Deferring to `afterCommit` removes that race and introduces a smaller one — the process can die
between commit and send, and the event is lost. That is an **at-most-once** gap, and it is the
honest boundary of this implementation.

**The real fix is a transactional outbox**: write the event to a table in the same transaction
as the order, and have a separate relay publish and mark it sent. That makes the write and the
intent-to-publish atomic, at the cost of a table, a relay process, and consumers that must
tolerate duplicates. It was left out because implementing it badly is worse than naming it
clearly.

## 5. The saga that is not here

`OrderPlacedListener` reserves stock line by line and rejects the whole order if any line fails.
Lines already reserved are **not** released. A rejected order can therefore consume stock.

This is stated in the code rather than hidden because it is the defining decision of
event-driven order flows, and there is no free answer:

- *One transaction across all lines* — simple, but serialises the hot SKUs.
- *Compensating release on rejection* — the classic saga; needs idempotency keys, because the
  release can be delivered twice.
- *Reserve-then-confirm with a TTL* — best behaviour, most machinery.

The optimistic `@Version` lock on `StockItem` covers the smaller concurrency problem underneath:
two listener threads handling the same SKU from different partitions would otherwise lose a
decrement to a read-modify-write race, and inventory would drift upward over time.

## 6. Caching, and why it needs its own tenant story

Redis sits outside Hibernate, so `@TenantId` does not reach it. Every cached read on
tenant-scoped data is therefore a fresh chance to leak, and the leak only occurs on a hit —
which is why it never reproduces against a cold local cache.

Two rules follow, both enforced in `RedisCacheConfig` and `InventoryService`:

1. **The tenant is part of the key.** `stock::<tenant>:<sku>`, never `stock::<sku>`.
2. **A read and its eviction share one key expression.** Deriving keys from method arguments
   produces `tenant:SKU-DOCK` for `getStock(sku)` and `tenant:SKU-DOCK:3` for
   `reserve(sku, quantity)`. Nothing errors; the evict simply deletes a key that was never
   written and the stale value lives until the TTL.

The value serializer is bound to `StockLevel` rather than using default typing. That is partly
correctness — records are final, so Jackson's `NON_FINAL` typing writes no `@class` and then
demands one on read, breaking every cache *hit* — and partly security: default typing
instantiates whatever class the payload names, so anyone who can write to Redis chooses what
the service deserializes.

TTL is 10 minutes with eviction on write. The TTL is the backstop for the eviction that gets
missed, not the primary mechanism.

## 7. Authentication and authorization

**Roles.** Keycloak puts roles in `realm_access.roles` and `resource_access.<client>.roles`,
neither of which Spring's default converter reads — it looks at `scope`. Out of the box every
authenticated user therefore arrives with `SCOPE_profile` and every `hasRole(...)` fails. The
converter reads both and prefixes `ROLE_`, and filters client roles to *this* client's id so a
role granted on an unrelated client cannot leak in.

**Defence in depth.** The gateway authenticates, and both services authenticate again. Not
redundant: anything able to reach port 8081 on the internal network would otherwise be
unauthenticated-equivalent. The gateway is routing and throttling, not the security boundary.

**Token relay over Feign.** The caller's bearer token is forwarded on outbound calls rather
than using a service account. Authorization is then evaluated against the human who made the
request, instead of against a shared identity holding the union of everyone's permissions.
The trade-off is that a downstream call cannot exceed the caller's own rights — usually what
you want, occasionally an obstacle for genuine system operations, which is where a separate
service-account client belongs.

**Stateless.** No session, and CSRF disabled. A JWT API that also issues a `JSESSIONID` is a
CSRF surface with no benefit; with no cookie for a browser to attach automatically, a CSRF
token protects nothing and breaks every non-browser client.

## 8. Pagination with collections: the three strategies

The N+1 fix that matters is not "use `join fetch`" — it is knowing why `join fetch` plus
`Pageable` is worse.

```sql
-- NAIVE: 1 page query + 1 count + one of these per row
select ... from order_lines where order_id = ?

-- JOIN_FETCH: one statement, no LIMIT, whole table into memory
select distinct o.*, l.* from orders o left join order_lines l on l.order_id = o.id

-- TWO_QUERY: LIMIT stays in the database
select o.id from orders order by placed_at desc limit 20 offset 0
select distinct o.*, l.* from orders o left join order_lines l on l.order_id = o.id where o.id in (...)
```

`LIMIT` cannot be applied to the joined form without truncating a collection mid-order, so
Hibernate fetches everything and paginates in memory, warning `HHH90003004`. At 4,000 orders
that is 40,000 rows materialised to return 20.

`spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch` is set to
`false` here **so the trap can be demonstrated**. In a real application set it to `true`; it
turns this silent memory bomb into a startup-time exception.

Two related settings, both non-default and both deliberate:

- `spring.jpa.open-in-view: false`. The default keeps the persistence context open through
  view rendering, so a lazy collection touched during serialization issues queries after the
  controller returned — an N+1 that does not appear anywhere in the controller's code.
- The index `ix_order_lines_tenant_order`. Without it, `NAIVE` sequentially scans `order_lines`
  per order and `TWO_QUERY`'s `IN (...)` is no better; the comparison would be measuring a
  missing index rather than a fetch strategy.

## 9. Operational choices in the compose file

- **Health checks, not `sleep`.** Keycloak's realm import and Kafka's first leader election
  both take longer than any fixed delay you would guess.
- **Keycloak health lives on port 9000** since version 25, and the image ships neither curl nor
  wget — hence the `/dev/tcp` check.
- **`KC_HOSTNAME` is pinned** so `iss` is stable regardless of the address used to reach it,
  with `jwk-set-uri` pointing at the internal address. The issuer is compared; the JWKS URI is
  dialled. Conflating them is the single most common containerised-Keycloak failure.
- **Replication factor 1 everywhere.** On a single-broker KRaft cluster anything higher never
  becomes available, and the first producer send hangs until it times out.
- **Unusual host ports** (18180, 18080, 15432, 16379, 39092) so the stack does not collide with
  whatever else is running. All overridable by environment variable.
- **Services run as a non-root user** with `-XX:MaxRAMPercentage=70`. Without the latter the JVM
  sizes its heap from the host's memory and gets OOM-killed by a limit it never saw.

## 10. What would change for production

| Concern | Here | Production |
|---|---|---|
| Event delivery | `afterCommit` publish | Transactional outbox + idempotent consumers |
| Compensation | None; documented gap | Saga with compensating release |
| Contracts | Hand-copied records | Schema registry, compatibility enforced in CI |
| Secrets | Literals in compose | Externalised; no static client secret |
| Demo client | Public client, password grant | Authorization code + PKCE |
| Kafka | One broker, RF 1 | RF 3, `min.insync.replicas=2` |
| Isolation | `@TenantId` only | Plus Postgres row-level security |
| Observability | Actuator + Prometheus endpoint | Distributed tracing across the Kafka hop |
| Deployment | Compose | Kubernetes with real probes and budgets |
