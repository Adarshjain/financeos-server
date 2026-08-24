-- Flyway Migration V70: Chat Read-Only Views for LLM Chat Feature
--
-- Every tenant view filters on user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')
-- and never projects user_id, credentials, tokens, or ingestion-internal columns.
-- Views are column projections + the tenancy predicate only — no business logic.

-- 1. Transactions (exactly ONE row per transaction; categories aggregated so SUMs never double-count)
CREATE OR REPLACE VIEW v_chat_transactions AS
SELECT
    t.id,
    t.transaction_date,
    t.settlement_date,
    t.amount,
    t.type AS direction,
    t.description,
    t.sourced_description,
    t.mcc,
    t.channel,
    t.is_emi,
    t.is_international,
    t.is_under_monitoring,
    t.review_type,
    t.is_excluded,
    t.instant_discount,
    t.convenience_fee,
    cat.category_names,
    a.id AS account_id,
    a.name AS account_name,
    a.type AS account_type
FROM transactions t
LEFT JOIN (
    SELECT tc.transaction_id,
           LISTAGG(c.name, ', ') WITHIN GROUP (ORDER BY c.name) AS category_names
    FROM transaction_categories tc
    JOIN categories c ON c.id = tc.category_id
    GROUP BY tc.transaction_id
) cat ON cat.transaction_id = t.id
LEFT JOIN accounts a ON a.id = t.account_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 1b. Transaction↔category junction (use for accurate per-category grouping/joins)
CREATE OR REPLACE VIEW v_chat_transaction_categories AS
SELECT
    tc.transaction_id,
    c.id AS category_id,
    c.name AS category_name
FROM transaction_categories tc
JOIN categories c ON c.id = tc.category_id
JOIN transactions t ON t.id = tc.transaction_id
WHERE t.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 2. Accounts
CREATE OR REPLACE VIEW v_chat_accounts AS
SELECT
    a.id,
    a.name,
    a.type,
    a.exclude_from_net_asset,
    a.financial_position,
    a.description,
    cc.last4 AS cc_last4,
    cc.credit_limit AS cc_credit_limit,
    cc.payment_due_day AS cc_payment_due_day,
    b.last4 AS bank_last4,
    b.opening_balance AS bank_opening_balance,
    br.cash_balance AS broker_cash_balance
FROM accounts a
LEFT JOIN account_credit_card_details cc ON cc.account_id = a.id
LEFT JOIN account_bank_details b ON b.account_id = a.id
LEFT JOIN account_broker_details br ON br.account_id = a.id
WHERE a.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 3. Categories
CREATE OR REPLACE VIEW v_chat_categories AS
SELECT
    c.id,
    c.name
FROM categories c
WHERE c.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 4. Investment Trades
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
    i.name AS instrument_name,
    i.symbol AS instrument_symbol,
    i.isin AS instrument_isin,
    a.id AS account_id,
    a.name AS account_name
FROM investment_transactions it
LEFT JOIN holdings h ON h.id = it.holding_id
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE it.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 5. Holdings (instrument↔broker map only; quantity/cost/value are DERIVED — use compute tools)
CREATE OR REPLACE VIEW v_chat_holdings AS
SELECT
    h.id,
    h.broker_account_id AS account_id,
    a.name AS account_name,
    i.id AS instrument_id,
    i.name AS instrument_name,
    i.symbol AS instrument_symbol,
    i.isin AS instrument_isin
FROM holdings h
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE h.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 6. Dividends
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
    i.id AS instrument_id,
    i.name AS instrument_name,
    i.symbol AS instrument_symbol,
    a.name AS account_name
FROM dividends d
LEFT JOIN holdings h ON h.id = d.holding_id
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE d.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 7. FnO Trades (one row per closed round-trip; realized_pnl = sell_value - buy_value - total_charges)
CREATE OR REPLACE VIEW v_chat_fno_trades AS
SELECT
    f.id,
    f.broker_account_id AS account_id,
    f.trading_symbol,
    f.underlying_symbol,
    f.contract_type,
    f.option_type,
    f.strike_price,
    f.expiry_date,
    f.quantity,
    f.buy_value,
    f.sell_value,
    f.total_charges,
    f.realized_pnl,
    f.entry_date,
    f.exit_date
FROM fno_trades f
WHERE f.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 8. Loans
CREATE OR REPLACE VIEW v_chat_loans AS
SELECT
    l.id,
    l.name,
    l.loan_type,
    l.lender,
    l.principal,
    l.annual_rate_pct,
    l.rate_type,
    l.tenure_months,
    l.start_date,
    l.first_emi_date,
    l.emi_amount,
    l.status
FROM loans l
WHERE l.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_loan_payments AS
SELECT
    lp.id,
    lp.loan_id,
    lp.installment_seq,
    lp.payment_date,
    lp.amount
FROM loan_payments lp
WHERE lp.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

CREATE OR REPLACE VIEW v_chat_loan_charges AS
SELECT
    lc.id,
    lc.loan_id,
    lc.charge_type,
    lc.amount,
    lc.charge_date
FROM loan_charges lc
WHERE lc.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 9. Lendings (two-way personal lend/borrow ledger per counterparty, post-V48 redesign)
CREATE OR REPLACE VIEW v_chat_lendings AS
SELECT
    len.id,
    cp.name AS counterparty_name,
    len.direction,
    len.amount,
    len.entry_date,
    len.expected_return_date,
    len.notes
FROM lendings len
LEFT JOIN counterparties cp ON cp.id = len.counterparty_id
WHERE len.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

-- 10. Instruments (GLOBAL reference data — no tenant column exists)
CREATE OR REPLACE VIEW v_chat_instruments AS
SELECT
    i.id,
    i.name,
    i.symbol,
    i.isin,
    i.exchange,
    i.type,
    i.currency
FROM instruments i;

-- 11. Instrument Prices (GLOBAL reference data)
CREATE OR REPLACE VIEW v_chat_instrument_prices AS
SELECT
    ip.id,
    ip.instrument_id,
    ip.as_of,
    ip.close,
    ip.source
FROM instrument_prices ip;

-- Grant SELECT on all V_CHAT_* views to chat_ro (skipped where the user doesn't exist, e.g. dev DBs)
BEGIN
  FOR v IN (SELECT view_name FROM user_views WHERE view_name LIKE 'V_CHAT_%') LOOP
    BEGIN
      EXECUTE IMMEDIATE 'GRANT SELECT ON ' || v.view_name || ' TO chat_ro';
    EXCEPTION WHEN OTHERS THEN
      IF SQLCODE != -1917 THEN RAISE; END IF; -- ORA-01917: user does not exist
    END;
  END LOOP;
END;
/
