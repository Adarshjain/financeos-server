-- NPS payment linking: a dismissal is either entry-level ("this payment is not that contribution": entry_id set) or
-- transaction-level ("this payment is not NPS": entry_id null). The V156 primary key (user_id, transaction_id) allowed
-- only one dismissal per transaction, so the table gets a surrogate id key (it is a JPA entity now) and a unique index
-- over the three columns (Oracle treats two rows with the same user, transaction and a null entry as duplicates, so a
-- transaction-level dismissal stays unique).
--
-- Idempotent: the DDL swallows ORA-01430 (column already exists), ORA-02443 (constraint not found), ORA-02260 (table already
-- has a primary key), ORA-01442 (column already NOT NULL), ORA-02264 / ORA-02275 (constraint already exists), ORA-00955 (object already exists) and ORA-01408 (column list already indexed).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal ADD entry_id VARCHAR2(36)', -1430);
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal ADD id VARCHAR2(36)', -1430);
    EXECUTE IMMEDIATE 'UPDATE nps_link_dismissal SET id = LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '
        || '''([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})'', ''\1-\2-\3-\4-\5'')) WHERE id IS NULL';
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal ADD CONSTRAINT fk_nps_dismissal_entry '
        || 'FOREIGN KEY (entry_id) REFERENCES nps_entry(id) ON DELETE CASCADE', -2264, -2275);
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal DROP CONSTRAINT pk_nps_link_dismissal', -2443);
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal MODIFY id NOT NULL', -1442);
    run_ddl_swallowing('ALTER TABLE nps_link_dismissal ADD CONSTRAINT pk_nps_dismissal_id PRIMARY KEY (id)', -2260, -2264);
    run_ddl_swallowing('CREATE UNIQUE INDEX uq_nps_dismissal ON nps_link_dismissal (user_id, transaction_id, entry_id)',
        -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_dismissal_entry ON nps_link_dismissal (entry_id)', -955, -1408);
END;
/
