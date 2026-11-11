-- V178__corporate_action_detection.sql
-- Automatic corporate-action detection: the market events read from the exchange (global), how far back they are
-- loaded, the per-user suggestions (auto-applied or held for the user), where an action came from, a 'delisted'
-- action type, and the price status of an instrument.
--
-- Idempotent: tables, columns, indexes and constraints are created only when missing; the type CHECK is dropped
-- and re-created (a re-run swallows "constraint does not exist").

DECLARE
    n NUMBER;

    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE <> p_err1 THEN
                RAISE;
            END IF;
    END;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'MARKET_CORPORATE_EVENTS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE market_corporate_events (
            id VARCHAR2(36) PRIMARY KEY,
            source VARCHAR2(10) NOT NULL,
            symbol VARCHAR2(40) NOT NULL,
            isin VARCHAR2(20) NULL,
            company VARCHAR2(200) NULL,
            series VARCHAR2(10) NULL,
            ex_date DATE NOT NULL,
            subject VARCHAR2(500) NOT NULL,
            parsed_type VARCHAR2(20) NULL,
            ratio_from NUMBER(10) NULL,
            ratio_to NUMBER(10) NULL,
            clean NUMBER(1) NOT NULL,
            fetched_at TIMESTAMP NULL,
            CONSTRAINT uk_market_corp_events UNIQUE (source, symbol, ex_date, subject)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_MARKET_CORP_EVENTS_DATE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_market_corp_events_date ON market_corporate_events (ex_date)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_MARKET_CORP_EVENTS_SYMBOL';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_market_corp_events_symbol ON market_corporate_events (symbol)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'MARKET_EVENT_COVERAGE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE market_event_coverage (
            source VARCHAR2(10) PRIMARY KEY,
            covered_from DATE NULL,
            covered_to DATE NULL,
            updated_at TIMESTAMP NULL
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'CORPORATE_ACTION_SUGGESTIONS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE corporate_action_suggestions (
            id VARCHAR2(36) PRIMARY KEY,
            user_id VARCHAR2(36) NOT NULL,
            instrument_id VARCHAR2(36) NOT NULL,
            kind VARCHAR2(20) NOT NULL,
            ex_date DATE NOT NULL,
            ratio_from NUMBER(10) NULL,
            ratio_to NUMBER(10) NULL,
            subject VARCHAR2(500) NULL,
            status VARCHAR2(20) NOT NULL,
            reason VARCHAR2(30) NULL,
            corporate_action_id VARCHAR2(36) NULL,
            created_at TIMESTAMP NOT NULL,
            resolved_at TIMESTAMP NULL,
            CONSTRAINT fk_ca_sugg_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            CONSTRAINT fk_ca_sugg_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
            CONSTRAINT fk_ca_sugg_action FOREIGN KEY (corporate_action_id) REFERENCES corporate_actions(id) ON DELETE SET NULL,
            CONSTRAINT uk_ca_sugg_key UNIQUE (user_id, instrument_id, kind, ex_date)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_CA_SUGG_USER_STATUS';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_ca_sugg_user_status ON corporate_action_suggestions (user_id, status)';
    END IF;
    -- Child-side index of the instrument and action foreign keys (cascade / set-null deletes).
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_CA_SUGG_INSTRUMENT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_ca_sugg_instrument ON corporate_action_suggestions (instrument_id)';
    END IF;
    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_CA_SUGG_ACTION';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_ca_sugg_action ON corporate_action_suggestions (corporate_action_id)';
    END IF;

    -- corporate_actions.source: 'manual' (the user entered it) or 'auto' (detected from two sources).
    run_ddl_swallowing('ALTER TABLE corporate_actions ADD (source VARCHAR2(10) DEFAULT ''manual'' NOT NULL)', -1430);

    -- The type CHECK also allows 'delisted'.
    run_ddl_swallowing('ALTER TABLE corporate_actions DROP CONSTRAINT chk_corp_actions_type', -2443);
    EXECUTE IMMEDIATE 'ALTER TABLE corporate_actions ADD CONSTRAINT chk_corp_actions_type
        CHECK (type IN (''split'', ''bonus'', ''demerger'', ''merger'', ''delisted''))';

    -- instruments.price_status: null, or 'not_trading'.
    run_ddl_swallowing('ALTER TABLE instruments ADD (price_status VARCHAR2(20) NULL)', -1430);
    run_ddl_swallowing('ALTER TABLE instruments ADD (price_status_at TIMESTAMP NULL)', -1430);
END;
/
