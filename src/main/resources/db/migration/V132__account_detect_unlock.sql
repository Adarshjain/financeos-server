-- V132__account_detect_unlock.sql
-- Statement-PDF unlock by rule: the password rules a statement mail names (tokens only, e.g. "NAME4U+DDMM|CARD4") and the
-- rule that opened a protected PDF (tokens or "SAVED"; never a password); on suggestions, the exact credit limit read from
-- the latest statement and the rule that opened this number's statements (so a created account can get its password built
-- from the profile).
--
-- account_detect_state.unlock_gen / holder_gen: generations of retry requests (any change / a name change); the facts'
-- unlock_tried_gen / holder_checked_gen are the retry's progress cursor, so a capped pass resumes where it stopped.
-- account_suggestions.not_in_your_name: the statements of this number name a holder and none is the user (band MAYBE).
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
    run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_hint VARCHAR2(200))', -1430);
    run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_rule VARCHAR2(100))', -1430);
    run_ddl_swallowing('ALTER TABLE account_detect_state ADD (unlock_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);
    run_ddl_swallowing('ALTER TABLE account_detect_state ADD (holder_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);
    run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (unlock_tried_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);
    run_ddl_swallowing('ALTER TABLE account_detect_facts ADD (holder_checked_gen NUMBER(10) DEFAULT 0 NOT NULL)', -1430);
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (limit_amount NUMBER(15,2))', -1430);
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (password_rule VARCHAR2(100))', -1430);
    run_ddl_swallowing('ALTER TABLE account_suggestions ADD (not_in_your_name NUMBER(1) DEFAULT 0 NOT NULL)', -1430);
END;
/
