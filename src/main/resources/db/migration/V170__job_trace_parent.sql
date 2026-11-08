-- V170__job_trace_parent.sql
-- jobs.trace_parent: the W3C traceparent of the span that enqueued the job (NULL when tracing was off), so the
-- worker can run the job as a child of the request that caused it. Add-only.
--
-- Idempotent: ADD swallows ORA-01430 (column already exists).

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
    run_ddl_swallowing('ALTER TABLE jobs ADD trace_parent VARCHAR2(64)', -1430);
END;
/
