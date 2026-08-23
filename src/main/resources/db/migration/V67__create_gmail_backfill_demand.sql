ALTER SESSION DISABLE PARALLEL DML;

-- Create gmail_backfill_demand table
DECLARE
    tbl_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO tbl_count FROM user_tables WHERE table_name = 'GMAIL_BACKFILL_DEMAND';
    IF tbl_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE gmail_backfill_demand (
            user_id VARCHAR2(36) PRIMARY KEY,
            floor_date DATE NOT NULL,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_gbd_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )';
    END IF;
END;
/
