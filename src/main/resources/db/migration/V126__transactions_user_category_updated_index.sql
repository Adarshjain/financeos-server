-- V126__transactions_user_category_updated_index.sql
-- Covers the Sort-them freshness probe (count + max(updated_at) of a user's rows with no category) index-only;
-- user_id leads and is NOT NULL, so rows with a NULL category_key are in the index.
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
    run_ddl_swallowing('CREATE INDEX idx_txn_user_cat_updated ON transactions (user_id, category_key, updated_at)', -955, -1408);
END;
/
