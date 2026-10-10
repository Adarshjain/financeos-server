-- V96__instrument_asset_class.sql
-- Asset classes for allocation and tax rules on the global instrument master.
--   instruments.asset_class        : EQUITY | DEBT | HYBRID | GOLD | INTERNATIONAL | OTHER (NULL = not classified yet)
--   instruments.scheme_category    : mutual funds — the raw AMFI scheme-category header,
--                                    e.g. 'Open Ended Schemes(Equity Scheme - Large Cap Fund)'
--   instruments.asset_class_source : AMFI | RULE | MANUAL (MANUAL is never overwritten by a refresh)
-- Backfill: stocks are EQUITY by rule. Funds and ETFs are classified by the next price refresh
-- (AMFI header / name rules); readers apply the same rules on the fly until then.
--
-- Idempotent: ADD swallows ORA-01430 (column already exists), ADD CONSTRAINT swallows ORA-02264
-- (name already used) and ORA-02275 (such a constraint already exists); the backfill only touches
-- unclassified stocks. No date/time columns, so nothing here depends on the session time zone.

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
    run_ddl_swallowing('ALTER TABLE instruments ADD asset_class VARCHAR2(20)', -1430);
    run_ddl_swallowing('ALTER TABLE instruments ADD scheme_category VARCHAR2(200)', -1430);
    run_ddl_swallowing('ALTER TABLE instruments ADD asset_class_source VARCHAR2(10)', -1430);
    run_ddl_swallowing('ALTER TABLE instruments ADD CONSTRAINT chk_instruments_asset_class CHECK '
        || '(asset_class IS NULL OR asset_class IN (''EQUITY'', ''DEBT'', ''HYBRID'', ''GOLD'', ''INTERNATIONAL'', ''OTHER''))',
        -2264, -2275);
    run_ddl_swallowing('ALTER TABLE instruments ADD CONSTRAINT chk_instruments_asset_class_src CHECK '
        || '(asset_class_source IS NULL OR asset_class_source IN (''AMFI'', ''RULE'', ''MANUAL''))',
        -2264, -2275);
    EXECUTE IMMEDIATE 'UPDATE instruments SET asset_class = ''EQUITY'', asset_class_source = ''RULE'' '
        || 'WHERE type = ''stock'' AND asset_class IS NULL';
END;
/
