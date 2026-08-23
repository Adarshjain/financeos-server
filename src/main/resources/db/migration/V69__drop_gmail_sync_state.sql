ALTER SESSION DISABLE PARALLEL DML;

-- Drop obsolete gmail_sync_state table
DECLARE
    tbl_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO tbl_count FROM user_tables WHERE table_name = 'GMAIL_SYNC_STATE';
    IF tbl_count > 0 THEN
        EXECUTE IMMEDIATE 'DROP TABLE gmail_sync_state CASCADE CONSTRAINTS';
    END IF;
END;
/
