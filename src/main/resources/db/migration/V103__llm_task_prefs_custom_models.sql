-- V103__llm_task_prefs_custom_models.sql
-- A routing row can now be a user-typed model instead of a menu option: option_id = 'custom' with
-- provider + model filled in, plus optional per-model call knobs. The menu table is reused rather
-- than adding a models table, so a custom model is defined per task group.
--
-- uq_llm_task_prefs_opt (user_id, task_group, option_id) would allow only one 'custom' row per
-- group, so it goes; (user_id, task_group, position) still guarantees a clean order and the
-- service rejects duplicate menu options / duplicate (provider, model) pairs.
--
-- Idempotent: ORA-01430 (column exists) and ORA-02443 (constraint does not exist) are swallowed.

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD provider VARCHAR2(32)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD model VARCHAR2(255)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD structured_output VARCHAR2(16)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD reasoning_effort VARCHAR2(16)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD max_output_tokens NUMBER(10)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs ADD provider_pin VARCHAR2(64)';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE llm_task_prefs DROP CONSTRAINT uq_llm_task_prefs_opt';
EXCEPTION
    WHEN OTHERS THEN IF SQLCODE != -2443 THEN RAISE; END IF;
END;
/
