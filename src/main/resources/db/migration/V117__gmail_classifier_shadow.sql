-- V117__gmail_classifier_shadow.sql
-- Shadow email classifier: every fetched Gmail message gets a deterministic ALERT / STATEMENT / OTHER
-- answer stored next to what the existing pipeline decided (pipeline_kind), so the two can be compared.
-- Nothing reads these columns for routing yet.
--
-- Idempotent: each ADD COLUMN swallows ORA-01430 (column already in the table).

DECLARE
    PROCEDURE add_column(p_sql VARCHAR2) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != -1430 THEN
                RAISE;
            END IF;
    END;
BEGIN
    add_column('ALTER TABLE gmail_processed_messages ADD classified_kind VARCHAR2(16)');
    add_column('ALTER TABLE gmail_processed_messages ADD classified_subtype VARCHAR2(24)');
    add_column('ALTER TABLE gmail_processed_messages ADD classified_confidence VARCHAR2(8)');
    add_column('ALTER TABLE gmail_processed_messages ADD classified_bank VARCHAR2(32)');
    add_column('ALTER TABLE gmail_processed_messages ADD classified_signals VARCHAR2(500)');
    add_column('ALTER TABLE gmail_processed_messages ADD classifier_version NUMBER(5)');
    add_column('ALTER TABLE gmail_processed_messages ADD pipeline_kind VARCHAR2(16)');
END;
/
