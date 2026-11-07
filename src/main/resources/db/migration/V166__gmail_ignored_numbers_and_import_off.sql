-- 1. gmail_ignored_numbers: numbers (bank + last 4) the user chose to ignore in Gmail "Needs attention". A mail that
--    parks as unresolved with an ignored number is archived at once. bank_id is the catalog bank id, or 'unknown'
--    when the mail names none (an empty string is NULL in Oracle, so a sentinel keeps the unique key whole).
-- 2. Mail from an account with import off no longer lists one row per email: mail of a CLOSED account moves to
--    SKIPPED_BEFORE_WATERMARK, mail of an open account stays ACCOUNT_NOT_OPTED_IN but archived (the app shows one
--    grouped entry per account instead).
--
-- Idempotent: the table is created only when missing (ORA-00955 swallowed); the updates only touch rows still in the old state.

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != p_err1 THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE gmail_ignored_numbers (
        id          VARCHAR2(36) NOT NULL,
        user_id     VARCHAR2(36) NOT NULL,
        bank_id     VARCHAR2(40) NOT NULL,
        last4       VARCHAR2(4) NOT NULL,
        created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_gmail_ignored_numbers PRIMARY KEY (id),
        CONSTRAINT fk_gin_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uk_gin_user_bank_last4 UNIQUE (user_id, bank_id, last4)
    )', -955);
END;
/

UPDATE gmail_processed_messages
SET status = 'SKIPPED_BEFORE_WATERMARK', error = NULL, archived_at = NULL, attention_notified_at = NULL
WHERE status = 'ACCOUNT_NOT_OPTED_IN'
  AND account_id IN (SELECT id FROM accounts WHERE closed_on IS NOT NULL AND closed_on <= TRUNC(SYSDATE));

UPDATE gmail_processed_messages
SET archived_at = CURRENT_TIMESTAMP
WHERE status = 'ACCOUNT_NOT_OPTED_IN' AND archived_at IS NULL;
