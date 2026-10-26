-- V112__transactions_user_date_index.sql
-- Serves "newest N transactions for a user" (global search) without sorting the user's whole history.
--
-- Idempotent: swallows ORA-00955 (name already used) and ORA-01408 (column list already indexed).

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
    run_ddl_swallowing('CREATE INDEX idx_txn_user_date ON transactions (user_id, transaction_date DESC, id DESC)', -955, -1408);
END;
/
