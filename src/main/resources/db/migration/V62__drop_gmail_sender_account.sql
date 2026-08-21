-- Senders are user-level allowlist entries: one bank sender address serves multiple
-- cards/accounts, and transactions resolve their account from the email-extracted last4
-- (exactly-one rule). The per-sender account pin was only a resolution fallback that could
-- misattribute alerts, and the client never set it.
ALTER TABLE gmail_senders DROP (account_id) CASCADE CONSTRAINTS;
