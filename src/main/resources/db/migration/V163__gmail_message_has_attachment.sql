-- V163__gmail_message_has_attachment.sql
-- gmail_processed_messages.has_attachment: set at discovery for mails Gmail lists under has:attachment, so the drain
-- reads statement mails before the alerts they cover (fresh rows included). 1 = has an attachment.
--
-- Idempotent: ADD COLUMN swallows ORA-01430 (column exists).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE <> p_err1 THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('ALTER TABLE gmail_processed_messages ADD has_attachment NUMBER(1) DEFAULT 0 NOT NULL', -1430);
END;
/
