-- One Postgres instance, one database per service, and a role per service that cannot read
-- the other's database. Sharing a schema between microservices is the fastest way to lose the
-- ability to deploy them independently: the moment two services read the same table, every
-- migration becomes a coordinated release.

create user orders with password 'orders';
create database orders owner orders;

create user inventory with password 'inventory';
create database inventory owner inventory;
