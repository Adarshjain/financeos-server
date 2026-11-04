-- V149__recurring_series.sql
-- Detected series of regular payments (subscriptions, instalments, other): one row per user and merchant key.
-- Idempotent: the DDL swallows ORA-00955 (object already exists) and ORA-01408 (column list already indexed).

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
    run_ddl_swallowing('CREATE TABLE recurring_series (
        id              VARCHAR2(36) PRIMARY KEY,
        user_id         VARCHAR2(36) NOT NULL,
        merchant_key    VARCHAR2(255) NOT NULL,
        display_name    VARCHAR2(255) NOT NULL,
        kind            VARCHAR2(20) NOT NULL,
        period          VARCHAR2(20) NOT NULL,
        amount          NUMBER(19,4) NOT NULL,
        account_id      VARCHAR2(36),
        last_paid_on    DATE NOT NULL,
        next_due_on     DATE NOT NULL,
        status          VARCHAR2(20) NOT NULL,
        missed_cycles   NUMBER(3) DEFAULT 0 NOT NULL,
        loan_id         VARCHAR2(36),
        created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_recurring_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_recurring_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE SET NULL,
        CONSTRAINT fk_recurring_loan FOREIGN KEY (loan_id) REFERENCES loans(id) ON DELETE SET NULL,
        CONSTRAINT uq_recurring_user_merchant UNIQUE (user_id, merchant_key)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_recurring_user_status ON recurring_series (user_id, status)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_recurring_account ON recurring_series (account_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_recurring_loan ON recurring_series (loan_id)', -955, -1408);
END;
/
