-- Jupiter and Fi mail statements for accounts at their partner banks (federal, csb); CSB also mails from csb.bank.in.
--   1. bank_sender_domains gets csb.bank.in (csb), jupiter.money (jupiter) and fi.money (fi) so the sender's bank resolves.
--   2. Users with an open, import-on account (ingest_from_date set) at csb or federal get only these new domains as
--      senders: csb.bank.in for csb; fi.money for federal; jupiter.money for federal or csb. Bare domain, enabled,
--      bank_id = the account's bank, floored at the earliest ingest-from date of those accounts, unless the user already
--      has the address (any case) or removed it. Same shape as SenderAllowlistService.addBankSenders for a new account.
--      gmail_backfill_demand is untouched: setting an account's ingest date already ratcheted it to that date.
--
-- Idempotent: MERGE on domain; sender inserts only missing addresses.

MERGE INTO bank_sender_domains t
USING (SELECT 'csb' AS bank_id, 'csb.bank.in' AS domain FROM dual
       UNION ALL SELECT 'jupiter', 'jupiter.money' FROM dual
       UNION ALL SELECT 'fi', 'fi.money' FROM dual) s
ON (t.domain = s.domain)
WHEN NOT MATCHED THEN INSERT (id, bank_id, domain, created_at)
VALUES (LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
        s.bank_id, s.domain, CURRENT_TIMESTAMP);

INSERT INTO gmail_senders (id, user_id, name, sender_address, enabled, created_at, updated_at, bank_id, backfill_from)
SELECT LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
       x.user_id,
       CASE x.domain
            WHEN 'csb.bank.in' THEN 'CSB Bank'
            WHEN 'fi.money' THEN 'Fi Money'
            ELSE 'Jupiter'
       END,
       x.domain,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       x.bank,
       x.backfill_from
FROM (
    SELECT a.user_id, MIN(a.bank) AS bank, m.domain, MIN(a.ingest_from_date) AS backfill_from
    FROM accounts a
    JOIN (SELECT 'csb' AS bank, 'csb.bank.in' AS domain FROM dual
          UNION ALL SELECT 'federal', 'fi.money' FROM dual
          UNION ALL SELECT 'federal', 'jupiter.money' FROM dual
          UNION ALL SELECT 'csb', 'jupiter.money' FROM dual) m
      ON m.bank = a.bank
    WHERE a.closed_on IS NULL AND a.ingest_from_date IS NOT NULL
    GROUP BY a.user_id, m.domain
) x
WHERE NOT EXISTS (
    SELECT 1 FROM gmail_senders s
    WHERE s.user_id = x.user_id AND LOWER(s.sender_address) = x.domain)
  AND NOT EXISTS (
    SELECT 1 FROM gmail_sender_removals r
    WHERE r.user_id = x.user_id AND LOWER(r.sender_address) = x.domain);
