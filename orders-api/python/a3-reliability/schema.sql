CREATE TABLE IF NOT EXISTS orders (
  id         BIGSERIAL PRIMARY KEY,
  customer   TEXT        NOT NULL,
  currency   CHAR(3)     NOT NULL,
  items      JSONB       NOT NULL,
  total      BIGINT      NOT NULL,
  status     TEXT        NOT NULL,          -- pending | paying | paid
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS payments (
  id         BIGSERIAL PRIMARY KEY,
  order_id   BIGINT      NOT NULL REFERENCES orders (id),
  amount     BIGINT      NOT NULL,
  currency   CHAR(3)     NOT NULL,
  charge_id  TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per Idempotency-Key. The PRIMARY KEY is what makes "first request wins" atomic.
CREATE TABLE IF NOT EXISTS idempotency_keys (
  key             TEXT PRIMARY KEY,
  fingerprint     TEXT        NOT NULL,     -- hash of method + path + body
  state           TEXT        NOT NULL,     -- in_progress | completed
  response_status INT,
  response_body   TEXT,
  location        TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()   -- real systems expire keys after ~24h
);
