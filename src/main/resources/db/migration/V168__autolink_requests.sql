-- autolink_requests: pending auto-link work handed from an api node to the worker node through the DB (app.role=api
-- never runs auto-link itself). One row per user; from_date/to_date are the LinkScope span (both NULL = ALL, the whole
-- history). A later request widens the row (scope union, ALL wins) and bumps version; the worker claims a row with
-- DELETE ... WHERE user_id = ? AND version = ?, so a widen that lands mid-claim is not lost.
--
-- Idempotent: the table is created only when missing (ORA-00955 swallowed).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != p_err1 THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE autolink_requests (
        user_id       VARCHAR2(36) NOT NULL,
        from_date     DATE,
        to_date       DATE,
        version       NUMBER(19) DEFAULT 1 NOT NULL,
        requested_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_autolink_requests PRIMARY KEY (user_id),
        CONSTRAINT fk_alr_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )', -955);
END;
/
