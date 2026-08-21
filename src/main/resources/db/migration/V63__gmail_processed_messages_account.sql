-- Scope the Gmail processed-message ledger to the account it ingested into, so deleting an
-- account also clears its ingestion history (the email/statement can be re-ingested if the
-- account is re-added). Entries with no attributable account are failure/junk-mail history,
-- which is retryable by definition now that failures are no longer persisted.

-- ADB _high service auto-enables parallel DML, which deadlocked this migration's DELETE
-- across parallel servers (ORA-12801/ORA-12860). These tables are tiny; run serial.
ALTER SESSION DISABLE PARALLEL DML;

-- Self-heal: Oracle DDL auto-commits, so a previously failed run of this migration leaves
-- the column behind and the retry would die on ORA-01430. Drop it to start clean.
DECLARE
    col_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO col_count
    FROM user_tab_columns
    WHERE table_name = 'GMAIL_PROCESSED_MESSAGES' AND column_name = 'ACCOUNT_ID';
    IF col_count > 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE gmail_processed_messages DROP (account_id) CASCADE CONSTRAINTS';
    END IF;
END;
/

ALTER TABLE gmail_processed_messages ADD account_id VARCHAR2(36);

-- Backfill alert entries via their created transaction
UPDATE gmail_processed_messages gpm
SET account_id = (SELECT t.account_id FROM transactions t WHERE t.id = gpm.transaction_id)
WHERE gpm.transaction_id IS NOT NULL;

-- Backfill statement entries via statements.source_ref (holds the gmail message id)
UPDATE gmail_processed_messages gpm
SET account_id = (
    SELECT MAX(s.account_id)
    FROM statements s
    WHERE s.source = 'gmail'
      AND s.source_ref = gpm.gmail_message_id
      AND s.user_id = gpm.user_id
)
WHERE gpm.account_id IS NULL;

-- Unattributable rows (FAILED, junk mail, orphaned watermark skips) are dropped: they carry
-- no account fact and must not block re-ingestion.
DELETE FROM gmail_processed_messages WHERE account_id IS NULL;

ALTER TABLE gmail_processed_messages MODIFY account_id NOT NULL;
ALTER TABLE gmail_processed_messages ADD CONSTRAINT fk_gmail_proc_account
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE;
CREATE INDEX idx_gmail_proc_account ON gmail_processed_messages(account_id);
