-- What parser (version) produced a statement and exactly what it read, so a parser fix can be compared with stored data.
-- statements keeps only the version and when it was recorded; the snapshot (full account number, holder name,
-- narrations) lives encrypted (gzip, then AES-GCM bound to the statement id) in its own table, gone with its statement.
-- Null / absent for statements stored before this existed.
--
-- Idempotent: each column and the table are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'PARSER_VERSION';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (parser_version NUMBER(6) NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'STATEMENTS' AND column_name = 'PARSE_RECORDED_AT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE statements ADD (parse_recorded_at TIMESTAMP WITH TIME ZONE NULL)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'STATEMENT_PARSE_RECORDS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE statement_parse_records (
            statement_id VARCHAR2(36) PRIMARY KEY,
            parser_version NUMBER(6) NOT NULL,
            snapshot BLOB NOT NULL,
            snapshot_chars NUMBER(10) NOT NULL,
            recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
            CONSTRAINT fk_stmt_parse_rec_stmt FOREIGN KEY (statement_id) REFERENCES statements(id) ON DELETE CASCADE
        )';
    END IF;
END;
/
