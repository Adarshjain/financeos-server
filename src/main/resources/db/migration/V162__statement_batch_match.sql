-- V162__statement_batch_match.sql
-- Statement batch matching (wave 1):
--   transactions.original_amount / original_currency: a foreign charge's own amount and ISO currency (filled from wave 3).
--   transactions.not_on_statement_id: the statement whose period holds an alert that none of its lines paired with
--   (review reason NOT_ON_STATEMENT). No FK: deleting the statement clears the tag in code.
--
-- Idempotent: ADD COLUMN swallows ORA-01430 (column exists); CREATE INDEX swallows ORA-00955 / ORA-01408.

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
    run_ddl_swallowing('ALTER TABLE transactions ADD original_amount NUMBER(19,4) NULL', -1430);
    run_ddl_swallowing('ALTER TABLE transactions ADD original_currency VARCHAR2(3) NULL', -1430);
    run_ddl_swallowing('ALTER TABLE transactions ADD not_on_statement_id VARCHAR2(36) NULL', -1430);
    run_ddl_swallowing('CREATE INDEX idx_txn_not_on_statement ON transactions (not_on_statement_id)', -955, -1408);
END;
/
