-- A routing preference now references a routing OPTION, not a (provider, model) pair.
-- Two Gemini chain options both resolve to provider=gemini with no single model, so the old
-- shape could not tell them apart. option_id is the stable identity the user actually chose.
--
-- Backfilled rather than truncated: this table is new and unlikely to hold rows anywhere, but a
-- migration that silently discards user preferences is not one you want in the history.

ALTER TABLE llm_task_prefs ADD option_id VARCHAR2(64);

UPDATE llm_task_prefs
   SET option_id = CASE
       WHEN LOWER(provider) = 'gemini'     AND model IS NULL             THEN 'gemini-chain'
       WHEN LOWER(provider) = 'gemini'                                   THEN 'gemini-flash-chain'
       WHEN LOWER(provider) = 'openrouter' AND model = 'openrouter/free' THEN 'openrouter-free'
       WHEN LOWER(provider) = 'openrouter'                               THEN 'openrouter-glm'
       ELSE LOWER(provider)
   END
 WHERE option_id IS NULL;

-- Any row whose provider is no longer a configured option would pin an unresolvable id; drop those
-- rather than carry a preference the resolver has to silently ignore.
DELETE FROM llm_task_prefs WHERE option_id IS NULL;

ALTER TABLE llm_task_prefs MODIFY option_id NOT NULL;
ALTER TABLE llm_task_prefs DROP COLUMN provider;
ALTER TABLE llm_task_prefs DROP COLUMN model;

-- An option may appear at most once per group: the UI reorders a fixed set, never duplicates it.
ALTER TABLE llm_task_prefs ADD CONSTRAINT uq_llm_task_prefs_opt UNIQUE (user_id, task_group, option_id);
