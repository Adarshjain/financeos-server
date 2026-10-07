-- V87__lending_kind.sql
-- Ledger entries get a kind: 'principal' (new money lent/borrowed) or 'settlement' (a repayment
-- clearing an existing balance). direction keeps saying which way the money moved, so every
-- net / running-balance formula is unchanged; only the gross "total lent / total borrowed"
-- figures now exclude settlements. Existing rows default to principal — no backfill, nothing in
-- the data distinguishes a real borrow from a repayment recorded as one.
--
-- ADD ... DEFAULT ... NOT NULL is metadata-only on Oracle (no DML), so the V82 "DISABLE PARALLEL
-- DML" caveat does not apply. Idempotent: skips the ADD when the column already exists; the view
-- is CREATE OR REPLACE and the grant block is the V70/V86 pattern.

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_tab_columns
   WHERE table_name = 'LENDINGS' AND column_name = 'KIND';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE lendings ADD kind VARCHAR2(20) DEFAULT ''principal'' NOT NULL';
  END IF;
END;
/

CREATE OR REPLACE VIEW v_chat_lendings AS
SELECT
    len.id,
    cp.name AS counterparty_name,
    len.direction,
    len.kind,
    len.amount,
    len.entry_date,
    len.expected_return_date,
    len.transaction_id,
    len.notes
FROM lendings len
LEFT JOIN counterparties cp ON cp.id = len.counterparty_id
WHERE len.user_id = SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER');

BEGIN
  BEGIN
    EXECUTE IMMEDIATE 'GRANT SELECT ON v_chat_lendings TO chat_ro';
  EXCEPTION WHEN OTHERS THEN
    IF SQLCODE != -1917 THEN RAISE; END IF; -- ORA-01917: user does not exist
  END;
END;
/
