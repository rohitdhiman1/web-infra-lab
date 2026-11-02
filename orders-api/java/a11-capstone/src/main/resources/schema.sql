CREATE SCHEMA IF NOT EXISTS capstone;

CREATE TABLE IF NOT EXISTS capstone.orders (
  id         BIGSERIAL PRIMARY KEY,
  customer   TEXT        NOT NULL,
  currency   CHAR(3)     NOT NULL,
  items      JSONB       NOT NULL,
  total      BIGINT      NOT NULL,
  status     TEXT        NOT NULL,                 -- pending | paying | paid | cancelled
  owner      TEXT        NOT NULL DEFAULT '',      -- A10: the token's sub; who may see and change it
  version    INT         NOT NULL DEFAULT 1,       -- the ETag; bumped on every change, payments included
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- For databases created before A10 added owners. Older rows belong to nobody.
ALTER TABLE capstone.orders ADD COLUMN IF NOT EXISTS owner TEXT NOT NULL DEFAULT '';
-- "My orders, newest first" is the common query: owner, then id, matches it exactly.
CREATE INDEX IF NOT EXISTS orders_owner_id ON capstone.orders (owner, id DESC);

CREATE TABLE IF NOT EXISTS capstone.payments (
  id         BIGSERIAL PRIMARY KEY,
  order_id   BIGINT      NOT NULL REFERENCES capstone.orders (id),
  amount     BIGINT      NOT NULL,
  currency   CHAR(3)     NOT NULL,
  charge_id  TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS capstone.idempotency_keys (
  key             TEXT PRIMARY KEY,
  fingerprint     TEXT        NOT NULL,
  state           TEXT        NOT NULL,
  response_status INT,
  response_body   TEXT,
  location        TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
