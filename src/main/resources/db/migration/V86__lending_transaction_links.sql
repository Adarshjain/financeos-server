-- V86__lending_transaction_links.sql
-- Lending <-> transaction linking (plans/lending-transaction-linking-plan.md)
--
-- 1. Split bills: several ledger entries may share ONE bank transaction (Q1 = yes), so the
--    unique index on lendings.transaction_id becomes a plain index. Idempotent: only drops the
--    index when it still exists as UNIQUE, only creates the plain one when missing.
-- 2. Chat: expose transaction_id on v_chat_lendings so the read-only chat role can join the
--    ledger entry to the bank movement behind it. CREATE OR REPLACE is idempotent; the grant
--    block is copied from V70 (skips silently when chat_ro does not exist, e.g. dev DBs).
--
-- No DML here, so the V82 "DISABLE PARALLEL DML" caveat does not apply.

DECLARE
  n NUMBER;
BEGIN
  SELECT COUNT(*) INTO n FROM user_indexes
   WHERE index_name = 'IDX_LENDINGS_TXN_UNIQ' AND uniqueness = 'UNIQUE';
  IF n > 0 THEN
    EXECUTE IMMEDIATE 'DROP INDEX idx_lendings_txn_uniq';
  END IF;

  SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_LENDINGS_TXN';
  IF n = 0 THEN
    EXECUTE IMMEDIATE 'CREATE INDEX idx_lendings_txn ON lendings(transaction_id)';
  END IF;
END;
/

CREATE OR REPLACE VIEW v_chat_lendings AS
SELECT
    len.id,
    cp.name AS counterparty_name,
    len.direction,
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
