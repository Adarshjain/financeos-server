-- V182__sms_alert_source.sql
-- SMS sync, phase 2b: a transaction created from a text message has source 'sms_alert'. The check constraint on
-- transactions.source is widened to allow it. A wider constraint is safe for the previous release running against this
-- schema during a deploy.
--
-- The new constraint is added under a temporary name first, then the old one is dropped and the new one renamed, so
-- the column is never without a constraint.
--
-- Idempotent for every intermediate state: old only, old + temporary, temporary only, new under the final name (with
-- a leftover temporary), or already done.

DECLARE
    n_final NUMBER;
    n_tmp   NUMBER;
    cond    VARCHAR2(4000);
    done    BOOLEAN := FALSE;
BEGIN
    SELECT COUNT(*) INTO n_final FROM user_constraints WHERE constraint_name = 'CHK_TRANSACTIONS_SOURCE';
    SELECT COUNT(*) INTO n_tmp FROM user_constraints WHERE constraint_name = 'CHK_TRANSACTIONS_SOURCE_V2';
    IF n_final > 0 THEN
        SELECT search_condition INTO cond FROM user_constraints WHERE constraint_name = 'CHK_TRANSACTIONS_SOURCE';
        done := cond LIKE '%sms_alert%';
    END IF;

    IF done THEN
        IF n_tmp > 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source_v2';
        END IF;
    ELSE
        IF n_tmp = 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD CONSTRAINT chk_transactions_source_v2
                CHECK (source IN (''gmail_transaction_alert'', ''gmail_statement'', ''manual'', ''file_upload'', ''sms_alert''))';
        END IF;
        IF n_final > 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source';
        END IF;
        EXECUTE IMMEDIATE 'ALTER TABLE transactions RENAME CONSTRAINT chk_transactions_source_v2 TO chk_transactions_source';
    END IF;
END;
/
