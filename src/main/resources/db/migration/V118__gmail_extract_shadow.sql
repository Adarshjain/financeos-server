-- V118__gmail_extract_shadow.sql
-- Shadow offline extraction: every message the AI extracts also gets the rule engine's answer stored beside it,
-- plus which fields differ and whether a twin from the other side (issuer vs CRED/Paytm/Amazon Pay) exists.
-- Nothing reads these columns for routing.
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
    add_column('ALTER TABLE gmail_processed_messages ADD extract_tier VARCHAR2(10)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_template VARCHAR2(64)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_version NUMBER(5)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_amount NUMBER(19,4)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_currency VARCHAR2(3)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_direction VARCHAR2(8)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_date DATE');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_last4 VARCHAR2(4)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_counterparty VARCHAR2(255)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_mismatch VARCHAR2(100)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_ai_provider VARCHAR2(64)');
    add_column('ALTER TABLE gmail_processed_messages ADD extract_merge_txn VARCHAR2(36)');
END;
/
