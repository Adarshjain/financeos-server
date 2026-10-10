-- V99__user_instrument_repoints.sql
-- A user who changes an instrument's identifiers (ISIN / AMFI code / Yahoo symbol) is moved off the
-- shared catalog row onto another one (InstrumentRepointService). Their next broker import still
-- names the old instrument (by ISIN, AMFI code, Yahoo symbol, ticker or a catalog alias), so without
-- a record of the move the import would land on the old row again and duplicate the holding.
--   user_instrument_repoints : one row per (user, from_instrument) saying which instrument the user's
--                              imports of from_instrument now go to. Chained moves are kept flat
--                              (S->T then T->U stores S->U and T->U); moving back to S deletes the
--                              row. Removed with the user (ON DELETE CASCADE, as account deletion
--                              relies on) and with either instrument.
--
-- Idempotent: CREATE TABLE swallows ORA-00955 (name already used), CREATE INDEX swallows ORA-00955
-- and ORA-01408 (column list already indexed). created_at is TIMESTAMP WITH TIME ZONE (the entity
-- maps Instant), so nothing here depends on the session time zone.

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
    run_ddl_swallowing('CREATE TABLE user_instrument_repoints (
        id                  VARCHAR2(36) PRIMARY KEY,
        user_id             VARCHAR2(36) NOT NULL,
        from_instrument_id  VARCHAR2(36) NOT NULL,
        to_instrument_id    VARCHAR2(36) NOT NULL,
        created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_uir_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_uir_from FOREIGN KEY (from_instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
        CONSTRAINT fk_uir_to FOREIGN KEY (to_instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
        CONSTRAINT uq_uir_user_from UNIQUE (user_id, from_instrument_id),
        CONSTRAINT chk_uir_not_self CHECK (from_instrument_id <> to_instrument_id)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_uir_to ON user_instrument_repoints (to_instrument_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_uir_from ON user_instrument_repoints (from_instrument_id)', -955, -1408);
END;
/
