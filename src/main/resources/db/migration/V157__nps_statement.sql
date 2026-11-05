-- NPS statement import: one row per imported (account, tier, period) statement, the opening / closing
-- scheme units it printed, and the link from a ledger entry back to the statement it came from.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists), ORA-01408 (column list already indexed),
-- ORA-02275 (referential constraint already exists) and ORA-02264 (constraint name already used).

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
    run_ddl_swallowing('CREATE TABLE nps_statement (
        id                 VARCHAR2(36) PRIMARY KEY,
        user_id            VARCHAR2(36) NOT NULL,
        account_id         VARCHAR2(36) NOT NULL,
        tier               VARCHAR2(4) NOT NULL,
        period_start       DATE NOT NULL,
        period_end         DATE NOT NULL,
        layout             VARCHAR2(8),
        file_sha256        VARCHAR2(64),
        source             VARCHAR2(8) NOT NULL,
        gmail_message_id   VARCHAR2(200),
        value              NUMBER(16,2),
        contribution_count NUMBER(6),
        total_contribution NUMBER(16,2),
        total_withdrawal   NUMBER(16,2),
        notional_gain      NUMBER(16,2),
        xirr               NUMBER(8,2),
        units_mismatch     NUMBER(1) DEFAULT 0 NOT NULL,
        incomplete         NUMBER(1) DEFAULT 0 NOT NULL,
        warnings           CLOB,
        imported_at        TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_nps_statement_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_statement_account FOREIGN KEY (account_id) REFERENCES nps_account(id) ON DELETE CASCADE,
        CONSTRAINT ck_nps_statement_tier CHECK (tier IN (''I'', ''II'')),
        CONSTRAINT ck_nps_statement_source CHECK (source IN (''UPLOAD'', ''GMAIL''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_statement_user ON nps_statement (user_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_statement_account ON nps_statement (account_id)', -955, -1408);
    run_ddl_swallowing('CREATE UNIQUE INDEX uq_nps_statement_period '
        || 'ON nps_statement (account_id, tier, period_start, period_end)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE nps_statement_holding (
        id           VARCHAR2(36) PRIMARY KEY,
        statement_id VARCHAR2(36) NOT NULL,
        kind         VARCHAR2(8) NOT NULL,
        scheme_code  VARCHAR2(12) NOT NULL,
        units        NUMBER(16,4) NOT NULL,
        nav          NUMBER(12,4),
        nav_date     DATE,
        value        NUMBER(16,2),
        CONSTRAINT fk_nps_stmt_holding_stmt FOREIGN KEY (statement_id) REFERENCES nps_statement(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_stmt_holding_scheme FOREIGN KEY (scheme_code) REFERENCES nps_scheme(scheme_code),
        CONSTRAINT ck_nps_stmt_holding_kind CHECK (kind IN (''OPENING'', ''CLOSING''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_stmt_holding_stmt ON nps_statement_holding (statement_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_stmt_holding_scheme ON nps_statement_holding (scheme_code)', -955, -1408);

    -- A deleted statement leaves the entries it merely upgraded (their statement_id is null anyway) and,
    -- as a backstop, never blocks on an entry that still points at it.
    run_ddl_swallowing('ALTER TABLE nps_entry ADD CONSTRAINT fk_nps_entry_statement '
        || 'FOREIGN KEY (statement_id) REFERENCES nps_statement(id) ON DELETE SET NULL', -2275, -2264);
    run_ddl_swallowing('CREATE INDEX idx_nps_entry_statement ON nps_entry (statement_id)', -955, -1408);
END;
/
