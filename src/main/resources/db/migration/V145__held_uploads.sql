-- Statement files uploaded without an account that could not be matched to one (no match, several matches,
-- or locked). The file itself stays in job_artifacts (retained) until it is assigned, discarded or the job
-- is purged (30 days). All foreign keys cascade and are indexed.
--
-- Idempotent: the table and indexes are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'HELD_UPLOADS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE held_uploads (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            job_id VARCHAR2(36) NOT NULL,
            artifact_id VARCHAR2(36) NOT NULL,
            file_index NUMBER(10) NOT NULL,
            file_name VARCHAR2(255) NOT NULL,
            detected_bank_id VARCHAR2(32),
            detected_last4 VARCHAR2(8),
            reason VARCHAR2(16) NOT NULL,
            candidate_account_ids VARCHAR2(1000),
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            resolved_at TIMESTAMP WITH TIME ZONE,
            CONSTRAINT fk_held_uploads_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_held_uploads_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
            CONSTRAINT fk_held_uploads_artifact FOREIGN KEY (artifact_id) REFERENCES job_artifacts(id) ON DELETE CASCADE
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'HELD_UPLOADS' AND column_name = 'USER_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_held_uploads_user ON held_uploads (user_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'HELD_UPLOADS' AND column_name = 'JOB_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_held_uploads_job ON held_uploads (job_id)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'HELD_UPLOADS' AND column_name = 'ARTIFACT_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_held_uploads_artifact ON held_uploads (artifact_id)';
    END IF;
END;
/
