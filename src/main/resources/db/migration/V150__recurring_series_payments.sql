-- V150__recurring_series_payments.sql
-- The transactions that make up a recurring series; a transaction belongs to at most one series.
-- Idempotent: the DDL swallows ORA-00955 and ORA-01408.

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
    run_ddl_swallowing('CREATE TABLE recurring_series_payments (
        id              VARCHAR2(36) PRIMARY KEY,
        series_id       VARCHAR2(36) NOT NULL,
        transaction_id  VARCHAR2(36) NOT NULL,
        CONSTRAINT fk_recurring_pay_series FOREIGN KEY (series_id) REFERENCES recurring_series(id) ON DELETE CASCADE,
        CONSTRAINT fk_recurring_pay_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
        CONSTRAINT uq_recurring_pay_txn UNIQUE (transaction_id)
    )', -955);
END;
/
