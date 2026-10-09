-- H2-compatible test schema.
-- H2 in PostgreSQL mode uses IDENTITY() for auto-increment primary keys,
-- not BIGSERIAL or AUTO_INCREMENT. TIMESTAMP WITH TIME ZONE is supported.
-- Standard CHECK constraints and REFERENCES are preserved.

SET MODE PostgreSQL;

CREATE TABLE IF NOT EXISTS app_user (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(120) NOT NULL,
  email       VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Note: H2 does not support functional indexes (LOWER(email)).
-- The UNIQUE constraint on app_user.email handles uniqueness in tests.
-- The application normalizes emails to lowercase before inserting/querying.

CREATE TABLE IF NOT EXISTS portfolio (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  owner_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name        VARCHAR(120) NOT NULL,
  base_currency CHAR(3) NOT NULL DEFAULT 'USD',
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portfolio_owner ON portfolio(owner_id);

CREATE TABLE IF NOT EXISTS asset (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider_symbol VARCHAR(80) NOT NULL,
  ticker          VARCHAR(32) NOT NULL,
  name            VARCHAR(255) NOT NULL,
  exchange        VARCHAR(80),
  currency        VARCHAR(3),
  asset_type      VARCHAR(32),
  provider        VARCHAR(40) NOT NULL DEFAULT 'ALPHA_VANTAGE',
  UNIQUE(provider, provider_symbol)
);

CREATE TABLE IF NOT EXISTS investment_transaction (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  portfolio_id    BIGINT NOT NULL REFERENCES portfolio(id) ON DELETE CASCADE,
  asset_id        BIGINT NOT NULL REFERENCES asset(id),
  type            VARCHAR(8) NOT NULL CHECK(type IN ('BUY','SELL')),
  quantity        NUMERIC(24,8) NOT NULL CHECK(quantity > 0),
  execution_price NUMERIC(24,8) NOT NULL CHECK(execution_price > 0),
  fees            NUMERIC(20,8) NOT NULL DEFAULT 0 CHECK(fees >= 0),
  occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  notes           VARCHAR(1000),
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tx_portfolio_date ON investment_transaction(portfolio_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_tx_asset ON investment_transaction(asset_id);

CREATE TABLE IF NOT EXISTS watchlist_item (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  asset_id   BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, asset_id)
);

CREATE TABLE IF NOT EXISTS market_price_snapshot (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  asset_id           BIGINT NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
  price              NUMERIC(24,8) NOT NULL CHECK(price > 0),
  currency           VARCHAR(3),
  provider           VARCHAR(40) NOT NULL,
  provider_timestamp TIMESTAMP WITH TIME ZONE,
  retrieved_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  classification     VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_price_asset_retrieved ON market_price_snapshot(asset_id, retrieved_at DESC);
