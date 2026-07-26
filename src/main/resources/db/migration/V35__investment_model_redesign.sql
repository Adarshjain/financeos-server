-- Flyway Migration V35: Investment Model Redesign

-- 1. Drop obsolete 1:1 detail tables
DROP TABLE account_stock_details;
DROP TABLE account_mutual_fund_details;

-- 2. Drop old investment_transactions table (recreated below with new schema)
DROP TABLE investment_transactions;

-- 3. Update accounts.type check constraint for Oracle
ALTER TABLE accounts DROP CONSTRAINT chk_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_type CHECK (type IN ('bank_account', 'credit_card', 'broker', 'generic'));

-- 4. Create global instruments table (security master)
CREATE TABLE instruments (
    id VARCHAR2(36) PRIMARY KEY,
    type VARCHAR2(50) NOT NULL,
    name VARCHAR2(255) NOT NULL,
    symbol VARCHAR2(50),
    exchange VARCHAR2(20),
    isin VARCHAR2(50),
    amfi_code VARCHAR2(50),
    yahoo_symbol VARCHAR2(50),
    currency VARCHAR2(10) DEFAULT 'INR',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_instruments_type CHECK (type IN ('stock', 'mutual_fund', 'etf'))
);

CREATE UNIQUE INDEX uk_instruments_isin ON instruments (CASE WHEN isin IS NOT NULL THEN isin END);
CREATE INDEX idx_instruments_symbol ON instruments (symbol);
CREATE INDEX idx_instruments_amfi ON instruments (amfi_code);

-- 5. Create global instrument_prices table
CREATE TABLE instrument_prices (
    id VARCHAR2(36) PRIMARY KEY,
    instrument_id VARCHAR2(36) NOT NULL,
    as_of DATE NOT NULL,
    close NUMBER(19, 4) NOT NULL,
    source VARCHAR2(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inst_prices_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
    CONSTRAINT uk_inst_prices_inst_asof UNIQUE (instrument_id, as_of),
    CONSTRAINT chk_inst_prices_source CHECK (source IN ('AMFI', 'YAHOO', 'MANUAL'))
);

CREATE INDEX idx_inst_prices_inst ON instrument_prices (instrument_id);

-- 6. Create account_broker_details table (1:1 with accounts)
CREATE TABLE account_broker_details (
    account_id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    provider VARCHAR2(100) NOT NULL,
    client_id VARCHAR2(100),
    cash_balance NUMBER(19, 2) DEFAULT 0,
    CONSTRAINT fk_broker_details_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_broker_details_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- 7. Create holdings table
CREATE TABLE holdings (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    broker_account_id VARCHAR2(36) NOT NULL,
    instrument_id VARCHAR2(36) NOT NULL,
    notes VARCHAR2(4000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_holdings_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_holdings_broker FOREIGN KEY (broker_account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_holdings_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id),
    CONSTRAINT uk_holdings_user_broker_inst UNIQUE (user_id, broker_account_id, instrument_id)
);

CREATE INDEX idx_holdings_broker ON holdings (broker_account_id);
CREATE INDEX idx_holdings_instrument ON holdings (instrument_id);
CREATE INDEX idx_holdings_user_id ON holdings (user_id);

-- 8. Recreate investment_transactions table hanging off holdings
CREATE TABLE investment_transactions (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    holding_id VARCHAR2(36) NOT NULL,
    type VARCHAR2(10) NOT NULL,
    quantity NUMBER(19, 8) NOT NULL,
    price NUMBER(19, 4) NOT NULL,
    trade_date DATE NOT NULL,
    brokerage NUMBER(19, 4),
    stt NUMBER(19, 4),
    exchange_txn_charges NUMBER(19, 4),
    sebi_charges NUMBER(19, 4),
    stamp_duty NUMBER(19, 4),
    gst NUMBER(19, 4),
    dp_charges NUMBER(19, 4),
    other_charges NUMBER(19, 4),
    total_charges NUMBER(19, 4) DEFAULT 0,
    source VARCHAR2(50) DEFAULT 'manual',
    external_ref VARCHAR2(255),
    notes VARCHAR2(4000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inv_txns_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_inv_txns_holding FOREIGN KEY (holding_id) REFERENCES holdings(id) ON DELETE CASCADE,
    CONSTRAINT chk_inv_txns_type CHECK (type IN ('buy', 'sell')),
    CONSTRAINT chk_inv_txns_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inv_txns_price CHECK (price >= 0)
);

CREATE INDEX idx_inv_txns_holding ON investment_transactions (holding_id);
CREATE INDEX idx_inv_txns_date ON investment_transactions (trade_date);
CREATE INDEX idx_inv_txns_user_id ON investment_transactions (user_id);
