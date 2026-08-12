-- Opening stock for the two demo tenants.
--
-- Seeded through a migration rather than an endpoint so the live demo has something to read
-- the moment the stack is up, and so both tenants start from an identical position — which is
-- what makes "tenant-a's order did not move tenant-b's stock" a meaningful observation.

insert into stock_items (tenant_id, sku, available) values
    ('tenant-a', 'SKU-KEYBOARD', 500),
    ('tenant-a', 'SKU-MOUSE',    500),
    ('tenant-a', 'SKU-MONITOR',  120),
    ('tenant-a', 'SKU-DOCK',      80),
    ('tenant-a', 'SKU-CABLE',   1000),
    ('tenant-a', 'SKU-WEBCAM',   200),
    ('tenant-a', 'SKU-HEADSET',  300),
    ('tenant-a', 'SKU-STAND',    150),
    ('tenant-b', 'SKU-KEYBOARD', 500),
    ('tenant-b', 'SKU-MOUSE',    500),
    ('tenant-b', 'SKU-MONITOR',  120),
    ('tenant-b', 'SKU-DOCK',      80),
    ('tenant-b', 'SKU-CABLE',   1000),
    ('tenant-b', 'SKU-WEBCAM',   200),
    ('tenant-b', 'SKU-HEADSET',  300),
    ('tenant-b', 'SKU-STAND',    150);
