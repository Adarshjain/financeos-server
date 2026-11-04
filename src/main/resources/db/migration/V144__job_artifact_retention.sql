-- Stored statement files that must outlive a successful ingest job: files held for an account, files that
-- need a password. retained = 1 keeps the artifact when the job succeeds; file_index is the file's position in
-- the upload; file_sha256 lets "move statement" find the stored file of an ingested statement.
--
-- Idempotent: each column is added only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'JOB_ARTIFACTS' AND column_name = 'FILE_INDEX';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE job_artifacts ADD (file_index NUMBER(10))';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'JOB_ARTIFACTS' AND column_name = 'FILE_SHA256';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE job_artifacts ADD (file_sha256 VARCHAR2(64))';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'JOB_ARTIFACTS' AND column_name = 'RETAINED';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE job_artifacts ADD (retained NUMBER(1) DEFAULT 0 NOT NULL)';
    END IF;
END;
/
