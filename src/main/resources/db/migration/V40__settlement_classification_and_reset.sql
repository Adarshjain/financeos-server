-- Broker reconciliation model: raw executions + authoritative intraday classification.
-- Oracle dialect. FRESH START: existing investment data is cleared (equity + MF holdings),
-- per project decision (no data to preserve).

-- 1. Per-execution settlement label. NOTE: this is a display hint only — the holdings
--    math derives the intraday/delivery split from trade_settlement_classifications
--    (below), not from this column.
ALTER TABLE investment_transactions ADD settlement_type VARCHAR2(20) DEFAULT 'delivery' NOT NULL;

-- 2. Authoritative intraday split per (broker account, instrument, trade date), sourced
--    from the broker Tax P&L / Capital Gains "Intraday" section.
CREATE TABLE trade_settlement_classifications (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36) REFERENCES users(id),
    broker_account_id VARCHAR2(36) NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    holding_id VARCHAR2(36) REFERENCES holdings(id) ON DELETE CASCADE,
    instrument_id VARCHAR2(36) NOT NULL REFERENCES instruments(id),
    trade_date DATE NOT NULL,
    intraday_qty NUMBER(19, 8) NOT NULL,
    intraday_buy_value NUMBER(19, 4) NOT NULL,
    intraday_sell_value NUMBER(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tsc_acct_inst_date UNIQUE (broker_account_id, instrument_id, trade_date)
);

CREATE INDEX idx_tsc_holding ON trade_settlement_classifications (holding_id);

-- 3. Fresh reset. Oracle TRUNCATE cannot CASCADE across foreign keys, so delete rows in
--    child -> parent order (holdings' inbound FKs are ON DELETE CASCADE, but we clear
--    explicitly so this works regardless of constraint state).
DELETE FROM trade_settlement_classifications;
DELETE FROM investment_transactions;
DELETE FROM dividends;
DELETE FROM holdings;
