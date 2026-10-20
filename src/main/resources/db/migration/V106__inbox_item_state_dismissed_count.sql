-- V106__inbox_item_state_dismissed_count.sql
-- inbox_item_state.dismissed_count: for a dismissed summary row (review, gmail-attention), its count at
-- dismissal. The row comes back once its count differs. NULL on other rows and on dismissals made before this.
--
-- Idempotent: ADD swallows ORA-01430 (column already exists).

DECLARE
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE inbox_item_state ADD dismissed_count NUMBER(10)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -1430 THEN
            RAISE;
        END IF;
END;
/
