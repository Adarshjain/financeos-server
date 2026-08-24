-- ============================================================================
-- CHAT_RO setup — run ONCE as ADMIN on the Oracle ADB (manual step; NOT Flyway).
--
-- Why this user exists: the "Chat with your data" feature lets an LLM run
-- SELECT statements. Those run as CHAT_RO, which can see NOTHING except the
-- v_chat_* views, each of which filters rows to the current user via
-- SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER') (set per-connection by the app).
-- Tenant isolation therefore holds at the database layer regardless of what
-- SQL the LLM produces.
--
-- INVARIANT: CHAT_RO must NEVER receive any grant beyond CREATE SESSION here
-- and SELECT on v_chat_* views (granted idempotently by Flyway V70).
--
-- Steps:
--   1. Replace the password below with a strong generated one.
--      ADB's mandatory profile requires: 12-30 chars, at least 1 uppercase,
--      1 lowercase, and 1 digit; must not contain the username or "admin".
--      Keep it double-quoted; plain letters+digits avoids shell/JDBC escaping pain.
--   2. Run this script as ADMIN.
--   3. Put the credentials in server/.env and the OCI environment:
--        CHAT_RO_USERNAME=CHAT_RO
--        CHAT_RO_PASSWORD=<the password>
--   4. Restart the app; Flyway V70 (re)applies the view grants.
-- ============================================================================

CREATE USER chat_ro IDENTIFIED BY "REPLACE_WITH_STRONG_PASSWORD";
GRANT CREATE SESSION TO chat_ro;

-- Apply view grants here too (not only in Flyway V70): if V70 already ran while
-- chat_ro did not exist, its guarded grant block was skipped and Flyway will not
-- re-run it. This loop is idempotent. Replace FINANCEOS with the app schema if different.
BEGIN
  FOR v IN (SELECT owner, view_name FROM all_views
            WHERE owner = 'FINANCEOS' AND view_name LIKE 'V_CHAT_%') LOOP
    EXECUTE IMMEDIATE 'GRANT SELECT ON ' || v.owner || '.' || v.view_name || ' TO chat_ro';
  END LOOP;
END;
/
