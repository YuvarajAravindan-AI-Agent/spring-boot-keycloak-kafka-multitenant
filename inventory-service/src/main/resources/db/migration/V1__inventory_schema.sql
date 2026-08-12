-- Inventory owns its own database. orders-service has no credentials for it and reaches it
-- only through the API or a topic, which is what keeps the two independently deployable.

create table stock_items (
    id        bigserial   primary key,
    tenant_id varchar(64) not null,
    sku       varchar(64) not null,
    available integer     not null check (available >= 0),
    version   bigint      not null default 0
);

create unique index ux_stock_tenant_sku on stock_items (tenant_id, sku);
