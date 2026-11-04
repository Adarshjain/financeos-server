-- Bank sender domains become data (were the static BankSenderDomains map), plus Kotak.
-- Also: gmail_senders.bank_id (the bank a sender was added for) and gmail_sender_removals (senders a user
-- deleted that were added for a bank, so auto-add never re-adds them).
--
-- Idempotent: tables and the column are created only when missing; the seed is a MERGE on (bank_id, domain).

DECLARE
    PROCEDURE ddl_if_missing(p_table VARCHAR2, p_sql VARCHAR2) IS
        n NUMBER;
    BEGIN
        SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = UPPER(p_table);
        IF n = 0 THEN
            EXECUTE IMMEDIATE p_sql;
        END IF;
    END;
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
    ddl_if_missing('bank_sender_domains', 'CREATE TABLE bank_sender_domains (
        id VARCHAR2(36) PRIMARY KEY,
        bank_id VARCHAR2(40) NOT NULL,
        domain VARCHAR2(255) NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT uk_bank_sender_domains UNIQUE (bank_id, domain)
    )');

    ddl_if_missing('gmail_sender_removals', 'CREATE TABLE gmail_sender_removals (
        id VARCHAR2(36) PRIMARY KEY,
        user_id VARCHAR2(36) NOT NULL,
        sender_address VARCHAR2(320) NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_gsr_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT uk_gsr_user_address UNIQUE (user_id, sender_address)
    )');

    add_column('ALTER TABLE gmail_senders ADD bank_id VARCHAR2(40)');
END;
/

MERGE INTO bank_sender_domains t
USING (
        SELECT 'hdfc' AS bank_id, 'hdfcbank.net' AS domain FROM dual
        UNION ALL SELECT 'hdfc' AS bank_id, 'hdfcbank.com' AS domain FROM dual
        UNION ALL SELECT 'hdfc' AS bank_id, 'hdfcbank.bank.in' AS domain FROM dual
        UNION ALL SELECT 'hdfc' AS bank_id, 'hdfc.bank.in' AS domain FROM dual
        UNION ALL SELECT 'icici' AS bank_id, 'icicibank.com' AS domain FROM dual
        UNION ALL SELECT 'icici' AS bank_id, 'icici.bank.in' AS domain FROM dual
        UNION ALL SELECT 'axis' AS bank_id, 'axisbank.com' AS domain FROM dual
        UNION ALL SELECT 'axis' AS bank_id, 'axis.bank.in' AS domain FROM dual
        UNION ALL SELECT 'sbi' AS bank_id, 'sbi.co.in' AS domain FROM dual
        UNION ALL SELECT 'sbi' AS bank_id, 'sbi.bank.in' AS domain FROM dual
        UNION ALL SELECT 'sbi' AS bank_id, 'sbicard.com' AS domain FROM dual
        UNION ALL SELECT 'yes' AS bank_id, 'yesbank.in' AS domain FROM dual
        UNION ALL SELECT 'yes' AS bank_id, 'yes.bank.in' AS domain FROM dual
        UNION ALL SELECT 'idfc_first' AS bank_id, 'idfcfirstbank.com' AS domain FROM dual
        UNION ALL SELECT 'idfc_first' AS bank_id, 'idfcfirst.bank.in' AS domain FROM dual
        UNION ALL SELECT 'federal' AS bank_id, 'federalbank.co.in' AS domain FROM dual
        UNION ALL SELECT 'federal' AS bank_id, 'federal.bank.in' AS domain FROM dual
        UNION ALL SELECT 'federal' AS bank_id, 'scapia.cards' AS domain FROM dual
        UNION ALL SELECT 'au' AS bank_id, 'aubank.in' AS domain FROM dual
        UNION ALL SELECT 'au' AS bank_id, 'au.bank.in' AS domain FROM dual
        UNION ALL SELECT 'equitas' AS bank_id, 'equitasbank.com' AS domain FROM dual
        UNION ALL SELECT 'indusind' AS bank_id, 'indusind.com' AS domain FROM dual
        UNION ALL SELECT 'indusind' AS bank_id, 'indusind.bank.in' AS domain FROM dual
        UNION ALL SELECT 'bank_of_baroda' AS bank_id, 'bankofbaroda.co.in' AS domain FROM dual
        UNION ALL SELECT 'bank_of_baroda' AS bank_id, 'bankofbaroda.bank.in' AS domain FROM dual
        UNION ALL SELECT 'bank_of_baroda' AS bank_id, 'bobcard.co.in' AS domain FROM dual
        UNION ALL SELECT 'bank_of_baroda' AS bank_id, 'bobfinancial.com' AS domain FROM dual
        UNION ALL SELECT 'canara' AS bank_id, 'canarabank.com' AS domain FROM dual
        UNION ALL SELECT 'indian_bank' AS bank_id, 'indianbank.co.in' AS domain FROM dual
        UNION ALL SELECT 'csb' AS bank_id, 'csb.co.in' AS domain FROM dual
        UNION ALL SELECT 'slice' AS bank_id, 'sliceit.com' AS domain FROM dual
        UNION ALL SELECT 'unity' AS bank_id, 'theunitybank.com' AS domain FROM dual
        UNION ALL SELECT 'amex' AS bank_id, 'americanexpress.com' AS domain FROM dual
        UNION ALL SELECT 'amex' AS bank_id, 'aexp.com' AS domain FROM dual
        UNION ALL SELECT 'hsbc' AS bank_id, 'hsbc.co.in' AS domain FROM dual
        UNION ALL SELECT 'hsbc' AS bank_id, 'hsbc.bank.in' AS domain FROM dual
        UNION ALL SELECT 'sbm' AS bank_id, 'sbmbank.co.in' AS domain FROM dual
        UNION ALL SELECT 'kotak' AS bank_id, 'kotak.com' AS domain FROM dual
        UNION ALL SELECT 'kotak' AS bank_id, 'kotak.bank.in' AS domain FROM dual
      ) s
ON (t.bank_id = s.bank_id AND t.domain = s.domain)
WHEN NOT MATCHED THEN INSERT (id, bank_id, domain, created_at)
VALUES (LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
        s.bank_id, s.domain, CURRENT_TIMESTAMP);
