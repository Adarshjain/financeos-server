-- V120__category_backfill_progress.sql
-- Progress of the one-off category backfill (wave 1b): per user, the last transaction id it has walked past
-- and counts, so a restart resumes where it stopped. A row with user_id '*' is the whole run's completion
-- marker (done = 1 means never enqueue again). backfill_key lets a later backfill reuse the table.
--
-- Idempotent: swallows ORA-00955 (name already used).

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
    run_ddl_swallowing('CREATE TABLE category_backfill_progress ('
        || 'backfill_key VARCHAR2(40) NOT NULL, '
        || 'user_id VARCHAR2(36) NOT NULL, '
        || 'last_txn_id VARCHAR2(36), '
        || 'scanned NUMBER(10) DEFAULT 0 NOT NULL, '
        || 'categorised NUMBER(10) DEFAULT 0 NOT NULL, '
        || 'done NUMBER(1) DEFAULT 0 NOT NULL, '
        || 'updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, '
        || 'CONSTRAINT pk_category_backfill_progress PRIMARY KEY (backfill_key, user_id))', -955);
END;
/
