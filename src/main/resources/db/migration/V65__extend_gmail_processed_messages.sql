ALTER SESSION DISABLE PARALLEL DML;

-- Make account_id nullable
ALTER TABLE gmail_processed_messages MODIFY account_id NULL;

-- Make processed_at nullable
ALTER TABLE gmail_processed_messages MODIFY processed_at NULL;

-- Add new metadata and tracking columns if not present
DECLARE
    col_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO col_count FROM user_tab_columns WHERE table_name = 'GMAIL_PROCESSED_MESSAGES' AND column_name = 'INTERNAL_DATE';
    IF col_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE gmail_processed_messages ADD (
            internal_date TIMESTAMP NULL,
            sender_address VARCHAR2(320) NULL,
            subject VARCHAR2(500) NULL,
            extracted_last4 VARCHAR2(4) NULL,
            attempt_count NUMBER(3) DEFAULT 0 NOT NULL,
            next_retry_at TIMESTAMP NULL,
            discovered_at TIMESTAMP NULL
        )';
    END IF;
END;
/

-- Backfill discovered_at from processed_at for pre-existing rows
UPDATE gmail_processed_messages
SET discovered_at = processed_at
WHERE discovered_at IS NULL AND processed_at IS NOT NULL;

-- Migrate legacy status FAILED -> FAILED_PERMANENT
UPDATE gmail_processed_messages
SET status = 'FAILED_PERMANENT'
WHERE status = 'FAILED';

-- Idempotent index creation
DECLARE
    idx_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO idx_count FROM user_indexes WHERE index_name = 'IDX_GPM_CONN_STAT_DISC';
    IF idx_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_gpm_conn_stat_disc ON gmail_processed_messages(connection_id, status, discovered_at)';
    END IF;

    SELECT COUNT(*) INTO idx_count FROM user_indexes WHERE index_name = 'IDX_GPM_USER_STAT_LAST4';
    IF idx_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_gpm_user_stat_last4 ON gmail_processed_messages(user_id, status, extracted_last4)';
    END IF;
END;
/
