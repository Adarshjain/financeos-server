-- Corrections to the bank-sender work (V134, V135).
--   1. gmail_senders.backfill_from: a sender added for an account never backfills Gmail before that account's date.
--   2. Removes the bare-domain senders V135 added for every user with an account at a bank, but only the ones that
--      are safe to drop: tagged with a bank, bare domain of that bank, created around V135's run (+/- 1 day), no
--      open account of that user at the bank imports from Gmail, and their sync cursors never progressed (a cursor
--      row is only updated once a sync lists or backfills with it). Everything else stays untouched; cursors cascade.
--      From now on bank senders are added only for accounts that import from Gmail.
--   3. UNIQUE (domain) on bank_sender_domains: a domain belongs to one bank.
--
-- Idempotent: the column and constraint are added only when missing; the delete only matches rows that still
-- satisfy the conditions (a re-run finds none).

DECLARE
    n NUMBER;
    v135_at TIMESTAMP;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tab_columns WHERE table_name = 'GMAIL_SENDERS' AND column_name = 'BACKFILL_FROM';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE gmail_senders ADD backfill_from DATE';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'FLYWAY_SCHEMA_HISTORY';
    IF n > 0 THEN
        EXECUTE IMMEDIATE 'SELECT MAX(installed_on) FROM flyway_schema_history WHERE version = ''135'' AND success = 1'
            INTO v135_at;
        IF v135_at IS NOT NULL THEN
            DELETE FROM gmail_senders s
            WHERE s.bank_id IS NOT NULL
              AND INSTR(s.sender_address, '@') = 0
              AND CAST(s.created_at AS TIMESTAMP) BETWEEN v135_at - INTERVAL '1' DAY AND v135_at + INTERVAL '1' DAY
              AND EXISTS (SELECT 1 FROM bank_sender_domains d
                          WHERE d.bank_id = s.bank_id AND d.domain = LOWER(s.sender_address))
              AND NOT EXISTS (SELECT 1 FROM accounts a
                              WHERE a.user_id = s.user_id AND a.bank = s.bank_id
                                AND a.ingest_from_date IS NOT NULL AND a.closed_on IS NULL)
              AND NOT EXISTS (SELECT 1 FROM gmail_sync_cursors c
                              WHERE c.sender_id = s.id AND c.updated_at > c.created_at);
        END IF;
    END IF;

    SELECT COUNT(*) INTO n FROM user_constraints
    WHERE table_name = 'BANK_SENDER_DOMAINS' AND constraint_name = 'UK_BSD_DOMAIN';
    IF n = 0 THEN
        SELECT COUNT(*) INTO n FROM user_ind_columns
        WHERE table_name = 'BANK_SENDER_DOMAINS' AND column_name = 'DOMAIN' AND column_position = 1
          AND index_name IN (SELECT index_name FROM user_indexes WHERE uniqueness = 'UNIQUE');
        IF n = 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE bank_sender_domains ADD CONSTRAINT uk_bsd_domain UNIQUE (domain)';
        END IF;
    END IF;
END;
/
