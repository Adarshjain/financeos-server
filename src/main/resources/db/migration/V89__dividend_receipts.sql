-- V89__dividend_receipts.sql
-- Dividend receipt reconciliation: did the payout a dividend row predicts actually land?
--
-- 1. dividends.transaction_id — the bank credit the payout arrived as (direct FK like
--    lendings.transaction_id, ON DELETE SET NULL: a duplicate credit can be deleted, the dividend
--    row is still true). Plain (non-unique) index: an interim + special dividend on one record date
--    arrive as a single credit, so several rows may share one transaction.
-- 2. dividends.receipt_status — manual override only: 'received_untracked' (landed in a bank account
--    FinanceOS does not track) or 'not_received' (confirmed missing). Every other status (received /
--    awaiting / overdue / unverifiable) is derived at read time, so it is deliberately NOT a column.
-- 3. v_chat_dividends gains source, transaction_id and receipt_status so chat can tell expected from
--    received (CREATE OR REPLACE; grant block is the V70/V86/V87 pattern).
--
-- DDL only — no DML, so the V82 "DISABLE PARALLEL DML" caveat does not apply. Idempotent: every
-- step guards on the data dictionary (user_tab_columns / user_constraints / user_ind_columns) so a
-- half-applied run can simply be re-run. The index guard checks for ANY index leading on the column,
-- not an index name (a constraint-backed index would already cover it).

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'DIVIDENDS' AND column_name = 'TRANSACTION_ID';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dividends ADD transaction_id VARCHAR2(36)';
  END IF;

  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'DIVIDENDS' AND column_name = 'RECEIPT_STATUS';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dividends ADD receipt_status VARCHAR2(20)';
  END IF;

  SELECT COUNT(*) INTO n FROM user_constraints
   WHERE table_name = 'DIVIDENDS' AND constraint_name = 'FK_DIVIDENDS_TXN';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dividends ADD CONSTRAINT fk_dividends_txn '
      || 'FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL';
  END IF;

  SELECT COUNT(*) INTO n FROM user_constraints
   WHERE table_name = 'DIVIDENDS' AND constraint_name = 'CHK_DIVIDENDS_RECEIPT_STATUS';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE dividends ADD CONSTRAINT chk_dividends_receipt_status '
      || 'CHECK (receipt_status IN (''received_untracked'', ''not_received''))';
  END IF;

  SELECT COUNT(*) INTO n FROM user_ind_columns
   WHERE table_name = 'DIVIDENDS' AND column_name = 'TRANSACTION_ID' AND column_position = 1;
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE INDEX idx_dividends_txn ON dividends(transaction_id)';
  END IF;
END;
/

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
    i.name AS instrument_name,
    i.symbol AS instrument_symbol,
    a.name AS account_name
FROM dividends d
LEFT JOIN holdings h ON h.id = d.holding_id
LEFT JOIN instruments i ON i.id = h.instrument_id
LEFT JOIN accounts a ON a.id = h.broker_account_id
WHERE d.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

BEGIN
  BEGIN
    EXECUTE IMMEDIATE 'GRANT SELECT ON v_chat_dividends TO chat_ro';
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE != -1917 THEN RAISE; END IF; -- ORA-01917: user does not exist
  END;
END;
/
