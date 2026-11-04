-- V151__recurring_series_payments_index.sql
-- Index the series FK of recurring_series_payments (the transaction FK is covered by its unique constraint).
-- Idempotent: swallows ORA-00955 and ORA-01408.

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
    run_ddl_swallowing('CREATE INDEX idx_recurring_pay_series ON recurring_series_payments (series_id)', -955, -1408);
END;
/
