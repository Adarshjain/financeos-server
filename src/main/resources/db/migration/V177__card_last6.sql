-- V177__card_last6.sql
-- account_credit_card_details.last6: the card's last 6 digits (encrypted), for banks whose statement password uses them.
--
-- Idempotent: ADD COLUMN swallows ORA-01430 (column exists).

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
    run_ddl_swallowing('ALTER TABLE account_credit_card_details ADD last6 VARCHAR2(500)', -1430);
END;
/
