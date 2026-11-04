-- One-time backfill for bank sender domains (V134).
--   1. Typed senders whose domain is (a subdomain of) a bank domain get that bank_id, so they group under the bank.
--      Rows and their sync cursors are otherwise untouched; unknown senders keep bank_id NULL.
--   2. Every user with an account at a bank gets that bank's domains as senders (bare domain, enabled, bank_id set)
--      unless they already have that address (case-insensitive). Sync cursors seed lazily on the next sync
--      (GmailSyncCursorService.getOrSeedCursors), so plain SQL is enough.
--
-- Idempotent: step 1 only touches rows with bank_id NULL; step 2 inserts only missing addresses.

UPDATE gmail_senders s
SET s.bank_id = (
    SELECT d.bank_id
    FROM bank_sender_domains d
    WHERE LOWER(CASE WHEN INSTR(s.sender_address, '@') > 0 THEN SUBSTR(s.sender_address, INSTR(s.sender_address, '@', -1) + 1) ELSE s.sender_address END) = d.domain
       OR LOWER(CASE WHEN INSTR(s.sender_address, '@') > 0 THEN SUBSTR(s.sender_address, INSTR(s.sender_address, '@', -1) + 1) ELSE s.sender_address END) LIKE '%.' || d.domain
    ORDER BY LENGTH(d.domain) DESC, d.bank_id
    FETCH FIRST 1 ROW ONLY)
WHERE s.bank_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM bank_sender_domains d
    WHERE LOWER(CASE WHEN INSTR(s.sender_address, '@') > 0 THEN SUBSTR(s.sender_address, INSTR(s.sender_address, '@', -1) + 1) ELSE s.sender_address END) = d.domain
       OR LOWER(CASE WHEN INSTR(s.sender_address, '@') > 0 THEN SUBSTR(s.sender_address, INSTR(s.sender_address, '@', -1) + 1) ELSE s.sender_address END) LIKE '%.' || d.domain);

INSERT INTO gmail_senders (id, user_id, name, sender_address, enabled, created_at, updated_at, bank_id)
SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
       ub.user_id,
       CASE ub.bank
            WHEN 'sbi' THEN 'State Bank of India'
            WHEN 'bank_of_baroda' THEN 'Bank of Baroda'
            WHEN 'canara' THEN 'Canara Bank'
            WHEN 'indian_bank' THEN 'Indian Bank'
            WHEN 'hdfc' THEN 'HDFC Bank'
            WHEN 'icici' THEN 'ICICI Bank'
            WHEN 'axis' THEN 'Axis Bank'
            WHEN 'indusind' THEN 'IndusInd Bank'
            WHEN 'yes' THEN 'Yes Bank'
            WHEN 'idfc_first' THEN 'IDFC First Bank'
            WHEN 'federal' THEN 'Federal Bank'
            WHEN 'csb' THEN 'CSB Bank'
            WHEN 'au' THEN 'AU Small Finance Bank'
            WHEN 'equitas' THEN 'Equitas Small Finance Bank'
            WHEN 'slice' THEN 'slice Small Finance Bank'
            WHEN 'unity' THEN 'Unity Small Finance Bank'
            WHEN 'amex' THEN 'American Express'
            WHEN 'hsbc' THEN 'HSBC'
            WHEN 'sbm' THEN 'SBM Bank India'
            WHEN 'kotak' THEN 'Kotak Mahindra Bank'
            ELSE ub.bank
       END,
       d.domain,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       ub.bank
FROM (SELECT DISTINCT user_id, bank FROM accounts WHERE bank IS NOT NULL) ub
JOIN bank_sender_domains d ON d.bank_id = ub.bank
WHERE NOT EXISTS (
    SELECT 1 FROM gmail_senders s
    WHERE s.user_id = ub.user_id AND LOWER(s.sender_address) = d.domain);
