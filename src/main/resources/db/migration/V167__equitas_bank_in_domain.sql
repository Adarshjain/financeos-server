-- Equitas also mails from equitas.bank.in (the .bank.in domains like the other banks'); only equitasbank.com was seeded.
-- Domains match by suffix, so subdomains of it resolve too (and notification.hsbc.bank.in / mail.hsbc.co.in already
-- resolve to hsbc through hsbc.bank.in / hsbc.co.in).
--
-- Idempotent: MERGE on (bank_id, domain). Existing users' sender lists pick the new domain up through the bank's
-- sender auto-add, like V135 did for the first seed.

MERGE INTO bank_sender_domains t
USING (SELECT 'equitas' AS bank_id, 'equitas.bank.in' AS domain FROM dual) s
ON (t.bank_id = s.bank_id AND t.domain = s.domain)
WHEN NOT MATCHED THEN INSERT (id, bank_id, domain, created_at)
VALUES (LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '([A-F0-9]{8})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{4})([A-F0-9]{12})', '\1-\2-\3-\4-\5')),
        s.bank_id, s.domain, CURRENT_TIMESTAMP);
