-- V105__pooled_cards_no_own_limit.sql
-- A card in a shared limit pool has no limit of its own (owner, 2026-10-19). The service now stores
-- null for pooled cards; clear the values saved before that so the chat views (cc_credit_limit) never
-- show a stale own limit. Re-running finds nothing to update.
UPDATE account_credit_card_details
   SET credit_limit = NULL
 WHERE limit_pool_id IS NOT NULL
   AND credit_limit IS NOT NULL;
