-- V164__transaction_amount_nullable.sql
-- transactions.amount nullable: a foreign charge (original_amount / original_currency, V162) waits for its statement
-- line to learn the rupee amount. Rupee rows still always carry one (enforced in code). chk_transactions_amount
-- (amount > 0) stays: a NULL passes a CHECK.
--
-- Idempotent: MODIFY ... NULL swallows ORA-01451 (column already nullable).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE <> p_err1 THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('ALTER TABLE transactions MODIFY amount NULL', -1451);
END;
/
