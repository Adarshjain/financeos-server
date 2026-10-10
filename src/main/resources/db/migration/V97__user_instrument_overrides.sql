-- V97__user_instrument_overrides.sql
-- Asset-class overrides are per user. The instrument master is global, so an override stored on
-- instruments (V96's asset_class_source = 'MANUAL') changed every user's allocation and tax class.
--   user_instrument_overrides : one row per (user, instrument) pinning that user's asset class
--                               (EQUITY | DEBT | HYBRID | GOLD | INTERNATIONAL | OTHER).
--                               Removed with the user (ON DELETE CASCADE, as account deletion relies
--                               on) and with the instrument.
-- Clean-up: any instrument still carrying a global MANUAL class (written only by the uncommitted
-- V96-era PATCH) drops back to derived — stocks to EQUITY/RULE (V96's backfill), everything else to
-- unclassified, which readers derive on the fly and the next price refresh stores (AMFI/RULE).
--
-- Idempotent: CREATE TABLE swallows ORA-00955 (name already used), CREATE INDEX swallows ORA-00955
-- and ORA-01408 (column list already indexed); the clean-up only matches MANUAL rows, so a re-run
-- updates nothing. Timestamps are TIMESTAMP WITH TIME ZONE (the entity maps Instant), so nothing
-- here depends on the session time zone.

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
    run_ddl_swallowing('CREATE TABLE user_instrument_overrides (
        id             VARCHAR2(36) PRIMARY KEY,
        user_id        VARCHAR2(36) NOT NULL,
        instrument_id  VARCHAR2(36) NOT NULL,
        asset_class    VARCHAR2(20) NOT NULL,
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_uio_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_uio_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
        CONSTRAINT uq_uio_user_instrument UNIQUE (user_id, instrument_id),
        CONSTRAINT chk_uio_asset_class CHECK
            (asset_class IN (''EQUITY'', ''DEBT'', ''HYBRID'', ''GOLD'', ''INTERNATIONAL'', ''OTHER''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_uio_instrument ON user_instrument_overrides (instrument_id)', -955, -1408);

    EXECUTE IMMEDIATE 'UPDATE instruments SET '
        || 'asset_class = CASE WHEN type = ''stock'' THEN ''EQUITY'' ELSE NULL END, '
        || 'asset_class_source = CASE WHEN type = ''stock'' THEN ''RULE'' ELSE NULL END '
        || 'WHERE asset_class_source = ''MANUAL''';
END;
/
