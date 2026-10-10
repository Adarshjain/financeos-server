-- V98__instrument_edits_per_user.sql
-- Editing an instrument is per user account: the instrument master, its feed prices and its import
-- aliases are shared by every user, so no user's edit may change what another user sees.
--   user_instrument_overrides : one row per (user, instrument) may now also pin the display fields
--                               name / symbol / exchange / currency / type (each NULL = the catalog
--                               value applies); asset_class becomes nullable so a row can override
--                               any subset.
--   instrument_prices.user_id : NULL = a feed (AMFI/YAHOO) row everyone sees; set = that user's own
--                               MANUAL price, seen only by them (it wins over a feed row of the same
--                               date). The (instrument_id, as_of) unique key becomes
--                               (instrument_id, as_of, user_id): Oracle compares a composite key with
--                               a NULL part on its non-NULL parts, so feed rows stay one per date.
--   instrument_aliases.user_id : NULL = a catalog alias (existing rows, import rename resolution);
--                               set = that user's own import hint.
-- Existing MANUAL prices were written before prices had an owner. One whose instrument is held by
-- exactly one user is attributed to that user; the rest stay global and read-only (no user can edit
-- or delete them through the API).
-- The chat views that read instruments apply the reader's overrides and hide other users' prices.
--
-- Idempotent: ADD swallows ORA-01430 (column already exists), MODIFY ... NULL swallows ORA-01451
-- (already nullable), ADD CONSTRAINT swallows ORA-02264/ORA-02275 (name / constraint already used),
-- DROP CONSTRAINT swallows ORA-02443 (no such constraint), CREATE INDEX swallows ORA-00955/ORA-01408
-- (name / column list already indexed); the attribution only touches unowned MANUAL rows whose
-- instrument has exactly one holder, so a re-run on the same data changes nothing further; views are
-- CREATE OR REPLACE. No date/time column is added or converted, so nothing here depends on the
-- session time zone.

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    -- Display overrides.
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides MODIFY asset_class NULL', -1451);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD name VARCHAR2(255)', -1430);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD symbol VARCHAR2(50)', -1430);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD exchange VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD currency VARCHAR2(10)', -1430);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD type VARCHAR2(50)', -1430);
    run_ddl_swallowing('ALTER TABLE user_instrument_overrides ADD CONSTRAINT chk_uio_type CHECK '
        || '(type IS NULL OR type IN (''stock'', ''mutual_fund'', ''etf''))', -2264, -2275);

    -- User-owned manual prices.
    run_ddl_swallowing('ALTER TABLE instrument_prices ADD user_id VARCHAR2(36)', -1430);
    run_ddl_swallowing('ALTER TABLE instrument_prices ADD CONSTRAINT fk_inst_prices_user '
        || 'FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE', -2264, -2275);
    run_ddl_swallowing('ALTER TABLE instrument_prices ADD CONSTRAINT chk_inst_prices_user_manual CHECK '
        || '(user_id IS NULL OR source = ''MANUAL'')', -2264, -2275);
    run_ddl_swallowing('ALTER TABLE instrument_prices DROP CONSTRAINT uk_inst_prices_inst_asof', -2443);
    run_ddl_swallowing('CREATE UNIQUE INDEX uk_inst_prices_inst_asof_usr ON instrument_prices '
        || '(instrument_id, as_of, user_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_inst_prices_user ON instrument_prices (user_id)', -955, -1408);

    -- User-owned import aliases.
    run_ddl_swallowing('ALTER TABLE instrument_aliases ADD user_id VARCHAR2(36)', -1430);
    run_ddl_swallowing('ALTER TABLE instrument_aliases ADD CONSTRAINT fk_inst_aliases_user '
        || 'FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE', -2264, -2275);
    run_ddl_swallowing('CREATE INDEX idx_inst_aliases_user ON instrument_aliases (user_id)', -955, -1408);

    -- Attribute legacy MANUAL prices whose instrument exactly one user holds.
    EXECUTE IMMEDIATE 'UPDATE instrument_prices p SET p.user_id = ('
        || '  SELECT MIN(h.user_id) FROM holdings h WHERE h.instrument_id = p.instrument_id) '
        || 'WHERE p.source = ''MANUAL'' AND p.user_id IS NULL '
        || '  AND (SELECT COUNT(DISTINCT h.user_id) FROM holdings h '
        || '       WHERE h.instrument_id = p.instrument_id AND h.user_id IS NOT NULL) = 1 '
        || '  AND NOT EXISTS (SELECT 1 FROM holdings h '
        || '       WHERE h.instrument_id = p.instrument_id AND h.user_id IS NULL)';
END;
/

-- Chat views: the reader's display overrides apply, and only feed prices plus the reader's own
-- manual prices are visible (the reader's row wins on a date both have).
CREATE OR REPLACE VIEW v_chat_investment_trades AS
SELECT
    it.id,
    it.holding_id,
    it.type AS side,
    it.settlement_type,
    it.quantity,
    it.price,
    it.trade_date,
    it.brokerage,
    it.stt,
    it.exchange_txn_charges,
    it.sebi_charges,
    it.stamp_duty,
    it.gst,
    it.dp_charges,
    it.other_charges,
    it.total_charges,
    i.id AS instrument_id,
    COALESCE(o.name, i.name) AS instrument_name,
    COALESCE(o.symbol, i.symbol) AS instrument_symbol,
    i.isin AS instrument_isin,
    a.id AS account_id,
    a.name AS account_name
FROM investment_transactions it
LEFT JOIN holdings h ON h.id = it.holding_id
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN user_instrument_overrides o ON o.instrument_id = i.id AND o.user_id = it.user_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE it.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_holdings AS
SELECT
    h.id,
    h.broker_account_id AS account_id,
    a.name AS account_name,
    i.id AS instrument_id,
    COALESCE(o.name, i.name) AS instrument_name,
    COALESCE(o.symbol, i.symbol) AS instrument_symbol,
    i.isin AS instrument_isin
FROM holdings h
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN user_instrument_overrides o ON o.instrument_id = i.id AND o.user_id = h.user_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE h.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_dividends AS
SELECT
    d.id,
    d.holding_id,
    d.type,
    d.amount,
    d.per_unit,
    d.tds,
    d.ex_date,
    d.pay_date,
    d.source,
    d.transaction_id,
    d.receipt_status,
    i.id AS instrument_id,
    COALESCE(o.name, i.name) AS instrument_name,
    COALESCE(o.symbol, i.symbol) AS instrument_symbol,
    a.name AS account_name
FROM dividends d
LEFT JOIN holdings h ON h.id = d.holding_id
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN user_instrument_overrides o ON o.instrument_id = i.id AND o.user_id = d.user_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE d.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_instruments AS
SELECT
    i.id,
    COALESCE(o.name, i.name) AS name,
    COALESCE(o.symbol, i.symbol) AS symbol,
    i.isin,
    COALESCE(o.exchange, i.exchange) AS exchange,
    COALESCE(o.type, i.type) AS type,
    COALESCE(o.currency, i.currency) AS currency
FROM instruments i
LEFT JOIN user_instrument_overrides o
    ON o.instrument_id = i.id AND o.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_instrument_prices AS
SELECT
    ip.id,
    ip.instrument_id,
    ip.as_of,
    ip.close,
    ip.source
FROM instrument_prices ip
WHERE (ip.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'))
   OR (ip.user_id IS NULL AND NOT EXISTS (
        SELECT 1 FROM instrument_prices own
        WHERE own.instrument_id = ip.instrument_id AND own.as_of = ip.as_of
          AND own.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')));

BEGIN
  FOR v IN (SELECT view_name FROM user_views WHERE view_name IN (
      'V_CHAT_INVESTMENT_TRADES', 'V_CHAT_HOLDINGS', 'V_CHAT_DIVIDENDS', 'V_CHAT_INSTRUMENTS',
      'V_CHAT_INSTRUMENT_PRICES')) LOOP
    BEGIN
      EXECUTE IMMEDIATE 'GRANT SELECT ON ' || v.view_name || ' TO chat_ro';
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE != -1917 THEN RAISE; END IF; -- ORA-01917: user does not exist
    END;
  END LOOP;
END;
/
