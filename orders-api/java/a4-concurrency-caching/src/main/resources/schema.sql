-- Each module from A4 on has its own Postgres schema, so labs never clash over table shapes.
CREATE SCHEMA IF NOT EXISTS a4;

CREATE TABLE IF NOT EXISTS a4.orders (
  id         BIGSERIAL PRIMARY KEY,
  customer   TEXT        NOT NULL,
  currency   CHAR(3)     NOT NULL,
  items      JSONB       NOT NULL,
  total      BIGINT      NOT NULL,
  status     TEXT        NOT NULL,
  version    INT         NOT NULL DEFAULT 1,   -- bumped on every change; the ETag is derived from it
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
