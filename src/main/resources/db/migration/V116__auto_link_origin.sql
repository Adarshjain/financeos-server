-- V116__auto_link_origin.sql
-- Auto-linking step 2 (EMI payments, lending settlements, dividend receipts): remember who made the
-- link. loan_payments.origin, lendings.origin and dividends.transaction_origin hold USER / AUTO
-- (default USER); an unlink of an AUTO link is remembered in auto_link_rejections.
-- Also resets users.auto_link_backfilled_at so the history backfill reruns with the new linkers
-- (runs are idempotent, so rerunning is safe).
--
-- Idempotent: each ADD COLUMN swallows ORA-01430 (column already in the table); the marker reset
-- is a plain UPDATE.

DECLARE
    PROCEDURE add_column(p_sql VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -1430 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_column('ALTER TABLE loan_payments ADD origin VARCHAR2(8) DEFAULT ''USER''');
    add_column('ALTER TABLE lendings ADD origin VARCHAR2(8) DEFAULT ''USER''');
    add_column('ALTER TABLE dividends ADD transaction_origin VARCHAR2(8) DEFAULT ''USER''');
END;
/

UPDATE users SET auto_link_backfilled_at = NULL;
