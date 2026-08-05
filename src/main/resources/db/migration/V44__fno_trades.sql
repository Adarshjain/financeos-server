CREATE TABLE fno_trades (
    id                VARCHAR2(36) PRIMARY KEY,
    user_id           VARCHAR2(36) NOT NULL,
    broker_account_id VARCHAR2(36) NOT NULL,
    trading_symbol    VARCHAR2(100) NOT NULL,
    underlying_symbol VARCHAR2(50),
    contract_type     VARCHAR2(10) NOT NULL,          -- future | option
    option_type       VARCHAR2(10),                   -- CE | PE (options only)
    strike_price      NUMBER(19,4),
    expiry_date       DATE,
    quantity          NUMBER(19,8) NOT NULL,
    buy_value         NUMBER(19,4) NOT NULL,
    sell_value        NUMBER(19,4) NOT NULL,
    total_charges     NUMBER(19,4) DEFAULT 0 NOT NULL,
    realized_pnl      NUMBER(19,4) NOT NULL,           -- sell_value - buy_value - total_charges
    entry_date        DATE,
    exit_date         DATE,
    source            VARCHAR2(20) DEFAULT 'manual' NOT NULL,  -- manual | import
    external_ref      VARCHAR2(200),
    notes             VARCHAR2(500),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_fno_broker FOREIGN KEY (broker_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_fno_trades_user ON fno_trades(user_id);
CREATE INDEX idx_fno_trades_broker ON fno_trades(broker_account_id);
