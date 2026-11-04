-- V143__close_prompt_dismissals.sql
-- "Is this loan closed?" / "Close this account?" prompts: when the user answered "Not yet", so the prompt rests
-- for 45 days (loans) / 90 days (accounts).
--
-- Idempotent: swallows ORA-01430 (column already exists).

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
    run_ddl_swallowing('ALTER TABLE loans ADD (closed_prompt_dismissed_at TIMESTAMP WITH TIME ZONE)', -1430);
    run_ddl_swallowing('ALTER TABLE accounts ADD (close_prompt_dismissed_at TIMESTAMP WITH TIME ZONE)', -1430);
END;
/
