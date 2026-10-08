-- V93__users_home_seeded_at.sql
-- users.home_seeded_at: one-shot gate for seeding a user's Home dashboard. NULL = not seeded yet;
-- the seeder claims it with a conditional UPDATE (… WHERE home_seeded_at IS NULL) so concurrent
-- first requests seed exactly once.
--
-- Idempotent: swallows ORA-01430 (column already exists).

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
    run_ddl_swallowing('ALTER TABLE users ADD home_seeded_at TIMESTAMP WITH TIME ZONE', -1430);
END;
/
