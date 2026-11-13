-- V186__corporate_scheme_evidence.sql
-- What the company's own exchange notices say about a demerger or merger: the child (or acquirer), the share ratio
-- and the cost split, each with the notice it came from. Global market data (no user_id), keyed by the parent's
-- exchange symbol, the kind and the ex-date (a merger has none: it is keyed by symbol and kind alone). Read by the
-- worker from the exchange's announcements; the Corporate actions page shows it next to the user's own entries.
--
-- Idempotent: tables and indexes are created only when missing.

DECLARE
    n NUMBER;
BEGIN
    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'CORPORATE_SCHEME_EVIDENCE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE corporate_scheme_evidence (
            id VARCHAR2(36) PRIMARY KEY,
            parent_symbol VARCHAR2(40) NOT NULL,
            parent_isin VARCHAR2(20) NULL,
            kind VARCHAR2(20) NOT NULL,
            ex_date DATE NULL,
            record_date DATE NULL,
            parent_cost_pct NUMBER(9,4) NULL,
            cost_kind VARCHAR2(10) NULL,
            cost_url VARCHAR2(500) NULL,
            cost_notice_date DATE NULL,
            cost_quote VARCHAR2(300) NULL,
            missing VARCHAR2(300) NULL,
            complete NUMBER(1) DEFAULT 0 NOT NULL,
            checked_at TIMESTAMP NOT NULL,
            next_check_at TIMESTAMP NULL,
            CONSTRAINT chk_scheme_evidence_kind CHECK (kind IN (''demerger'', ''merger'')),
            CONSTRAINT chk_scheme_evidence_cost_kind CHECK (cost_kind IS NULL OR cost_kind IN (''NOTICE'', ''ESTIMATE'')),
            CONSTRAINT uk_scheme_evidence UNIQUE (parent_symbol, kind, ex_date)
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SCHEME_EVIDENCE_NEXT';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_scheme_evidence_next ON corporate_scheme_evidence (next_check_at)';
    END IF;

    SELECT COUNT(*) INTO n FROM user_tables WHERE table_name = 'CORPORATE_SCHEME_CHILDREN';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE corporate_scheme_children (
            id VARCHAR2(36) PRIMARY KEY,
            evidence_id VARCHAR2(36) NOT NULL,
            child_order NUMBER(3) NOT NULL,
            child_symbol VARCHAR2(40) NOT NULL,
            child_isin VARCHAR2(20) NULL,
            child_name VARCHAR2(200) NOT NULL,
            listed_on DATE NULL,
            ratio_from NUMBER(10) NULL,
            ratio_to NUMBER(10) NULL,
            ratio_url VARCHAR2(500) NULL,
            ratio_notice_date DATE NULL,
            ratio_quote VARCHAR2(300) NULL,
            child_cost_pct NUMBER(9,4) NULL,
            CONSTRAINT fk_scheme_child_evidence FOREIGN KEY (evidence_id) REFERENCES corporate_scheme_evidence(id) ON DELETE CASCADE
        )';
    END IF;

    SELECT COUNT(*) INTO n FROM user_indexes WHERE index_name = 'IDX_SCHEME_CHILD_EVIDENCE';
    IF n = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_scheme_child_evidence ON corporate_scheme_children (evidence_id)';
    END IF;
END;
/
