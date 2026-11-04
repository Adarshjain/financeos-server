-- Lookup of a stored file by its hash ("move statement to another account"). Idempotent.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'JOB_ARTIFACTS' AND column_name = 'FILE_SHA256' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_job_artifacts_sha ON job_artifacts (file_sha256)';
    END IF;
END;
/
