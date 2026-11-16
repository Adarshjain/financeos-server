-- V188__user_change_stamp.sql
-- One row per user, bumped (version + 1) after every committed change to that user's data by either JVM
-- (API or worker). Per-JVM caches of derived per-user data (the inbox memo behind GET /shell/counts) compare
-- the version they were built at with the current one, so a change made on the other JVM is seen at once.
--   user_change_stamp : user_id (PK, removed with the user), changed_at, version.
--
-- Idempotent: CREATE TABLE swallows ORA-00955 (name already used). changed_at is TIMESTAMP WITH TIME ZONE
-- (the entity maps Instant), so nothing here depends on the session time zone.

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE user_change_stamp (
        user_id     VARCHAR2(36) PRIMARY KEY,
        changed_at  TIMESTAMP WITH TIME ZONE NOT NULL,
        version     NUMBER(19) NOT NULL,
        CONSTRAINT fk_ucs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )', -955);
END;
/
