-- Orders schema.
--
-- tenant_id is the discriminator for shared-schema multi-tenancy: one set of tables, one
-- connection pool, a tenant column on every row. Hibernate's @TenantId adds the predicate to
-- every statement, so isolation does not depend on anyone remembering to write a WHERE clause.

create table orders (
    id            bigserial      primary key,
    tenant_id     varchar(64)    not null,
    order_ref     varchar(64)    not null,
    customer_name varchar(255)   not null,
    placed_at     timestamptz    not null,
    status        varchar(32)    not null,
    total_amount  numeric(12, 2) not null default 0
);

create table order_lines (
    id         bigserial      primary key,
    tenant_id  varchar(64)    not null,
    order_id   bigint         not null references orders (id) on delete cascade,
    sku        varchar(64)    not null,
    quantity   integer        not null check (quantity > 0),
    unit_price numeric(12, 2) not null
);

-- Order references are unique per tenant, not globally: two tenants may legitimately both
-- have an ORD-0001, and a global unique constraint would leak that fact by rejecting the
-- second one.
create unique index ux_orders_tenant_ref on orders (tenant_id, order_ref);

-- Serves the listing endpoint's "newest first, this tenant only" access path. Leading with
-- tenant_id keeps the index usable as a range scan per tenant rather than a full scan and
-- filter.
create index ix_orders_tenant_placed_at on orders (tenant_id, placed_at desc);

-- Without this, the N+1 strategy performs a sequential scan of order_lines per order and the
-- two-query strategy's IN (...) is no better. The fetch strategy comparison is only
-- meaningful once the join column is indexed; otherwise it measures the missing index.
create index ix_order_lines_tenant_order on order_lines (tenant_id, order_id);
