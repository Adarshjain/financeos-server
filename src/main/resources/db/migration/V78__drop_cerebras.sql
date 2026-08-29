-- Cerebras is removed as a provider. Its free tier now requires a verified payment method and the
-- $5 trial credits expire after 30 days, so it cannot be offered to a BYOK user as a free option.

-- Routing rows for a removed option are already filtered out at read time; delete them so the
-- stored order matches what the UI shows.
DELETE FROM llm_task_prefs WHERE LOWER(option_id) = 'cerebras';

-- A stored Cerebras key can never be used again — the provider is gone from config, so the failover
-- loop skips it and the settings UI no longer renders a card that could delete it. Removing the
-- row here is what stops it becoming an unreachable stored credential.
DELETE FROM llm_api_keys WHERE LOWER(provider) = 'cerebras';
