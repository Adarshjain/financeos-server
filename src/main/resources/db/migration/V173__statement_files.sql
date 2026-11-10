-- The original statement file (PDF / Excel, exactly the bytes received, possibly password-locked), kept encrypted so a
-- statement can be re-parsed after a parser fix without asking the user to upload it again. One row per (user, file
-- hash): the same file received twice (upload and Gmail) is stored once and shared by its statements. A statement
-- points at its file through statements.file_id; deleting the file only clears that pointer.
--
-- Idempotent: the table, column, constraint and indexes are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'STATEMENT_FILES';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE statement_files (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            sha256 VARCHAR2(64) NOT NULL,
            size_bytes NUMBER(12) NOT NULL,
            file_type VARCHAR2(10) NOT NULL,
            original_name VARCHAR2(255) NULL,
            data BLOB NOT NULL,
            enc_version NUMBER(3) DEFAULT 1 NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT fk_stmt_files_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT uk_stmt_files_user_sha UNIQUE (user_id, sha256)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'FILE_ID';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (file_id VARCHAR2(36) NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_constraints
    WHERE table_name = 'STATEMENTS' AND constraint_name = 'FK_STATEMENTS_FILE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD CONSTRAINT fk_statements_file
            FOREIGN KEY (file_id) REFERENCES statement_files(id) ON DELETE SET NULL';
    END IF;

    SELECT COUNT(*) INTO n FROM user_ind_columns
    WHERE table_name = 'STATEMENTS' AND column_name = 'FILE_ID' AND column_position = 1;
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_statements_file ON statements (file_id)';
    END IF;
END;
/
