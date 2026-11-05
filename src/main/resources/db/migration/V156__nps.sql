-- NPS (National Pension System): the scheme catalog (each scheme is also an nps_scheme instrument priced from the
-- NPS Trust daily file), the user's NPS accounts (one per PRAN), their scheme allocation, and the unit ledger
-- (entries and their per-scheme lines), plus "not this one" dismissals of bank-transaction link suggestions.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists), ORA-01408 (column list already indexed) and,
-- for the instrument type check, ORA-02264 (constraint name already used) / ORA-02443 (constraint not found).

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
    -- instruments.type gains 'nps_scheme': drop and recreate the check (re-running recreates it the same).
    run_ddl_swallowing('ALTER TABLE instruments DROP CONSTRAINT chk_instruments_type', -2443);
    run_ddl_swallowing('ALTER TABLE instruments ADD CONSTRAINT chk_instruments_type '
        || 'CHECK (type IN (''stock'', ''mutual_fund'', ''etf'', ''nps_scheme''))', -2264);

    -- instrument_prices.source gains 'NPS_TRUST'.
    run_ddl_swallowing('ALTER TABLE instrument_prices DROP CONSTRAINT chk_inst_prices_source', -2443);
    run_ddl_swallowing('ALTER TABLE instrument_prices ADD CONSTRAINT chk_inst_prices_source '
        || 'CHECK (source IN (''AMFI'', ''YAHOO'', ''MANUAL'', ''NPS_TRUST''))', -2264);

    run_ddl_swallowing('CREATE TABLE nps_scheme (
        scheme_code   VARCHAR2(12) PRIMARY KEY,
        instrument_id VARCHAR2(36) NOT NULL,
        pfm_code      VARCHAR2(8),
        pfm_name      VARCHAR2(200 CHAR),
        asset_class   CHAR(1),
        tier          VARCHAR2(4),
        variant       VARCHAR2(8) NOT NULL,
        updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_nps_scheme_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
        CONSTRAINT uq_nps_scheme_instrument UNIQUE (instrument_id),
        CONSTRAINT ck_nps_scheme_class CHECK (asset_class IN (''E'', ''C'', ''G'', ''A'')),
        CONSTRAINT ck_nps_scheme_tier CHECK (tier IN (''I'', ''II'', ''TTS'')),
        CONSTRAINT ck_nps_scheme_variant CHECK (variant IN (''NONE'', ''DIRECT'', ''GS'', ''POP''))
    )', -955);

    run_ddl_swallowing('CREATE TABLE nps_account (
        id                           VARCHAR2(36) PRIMARY KEY,
        user_id                      VARCHAR2(36) NOT NULL,
        pran_encrypted               VARCHAR2(500) NOT NULL,
        pran_last4                   VARCHAR2(4) NOT NULL,
        holder_name                  VARCHAR2(200 CHAR) NOT NULL,
        relation                     VARCHAR2(8) NOT NULL,
        sector                       VARCHAR2(16) NOT NULL,
        cra                          VARCHAR2(10) NOT NULL,
        employer_id                  VARCHAR2(36),
        tier2_active                 NUMBER(1) DEFAULT 0 NOT NULL,
        scheme_choice                VARCHAR2(80 CHAR),
        status                       VARCHAR2(8) NOT NULL,
        read_gmail                   NUMBER(1) DEFAULT 1 NOT NULL,
        statement_password_encrypted VARCHAR2(500),
        created_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_nps_account_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_account_employer FOREIGN KEY (employer_id) REFERENCES epf_employer(id) ON DELETE SET NULL,
        CONSTRAINT ck_nps_account_relation CHECK (relation IN (''SELF'', ''CHILD'')),
        CONSTRAINT ck_nps_account_sector CHECK (sector IN (''ALL_CITIZEN'', ''CORPORATE'', ''GOVT'', ''VATSALYA'', ''UPS'')),
        CONSTRAINT ck_nps_account_cra CHECK (cra IN (''PROTEAN'', ''KFINTECH'', ''CAMS'')),
        CONSTRAINT ck_nps_account_status CHECK (status IN (''ACTIVE'', ''CLOSED''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_account_user ON nps_account (user_id)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE nps_allocation (
        id          VARCHAR2(36) PRIMARY KEY,
        account_id  VARCHAR2(36) NOT NULL,
        tier        VARCHAR2(4) NOT NULL,
        scheme_code VARCHAR2(12) NOT NULL,
        percent     NUMBER(5,2) NOT NULL,
        as_of       DATE NOT NULL,
        source      VARCHAR2(10) NOT NULL,
        CONSTRAINT fk_nps_allocation_account FOREIGN KEY (account_id) REFERENCES nps_account(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_allocation_scheme FOREIGN KEY (scheme_code) REFERENCES nps_scheme(scheme_code),
        CONSTRAINT ck_nps_allocation_tier CHECK (tier IN (''I'', ''II'')),
        CONSTRAINT ck_nps_allocation_source CHECK (source IN (''MANUAL'', ''STATEMENT''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_allocation_account ON nps_allocation (account_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_allocation_scheme ON nps_allocation (scheme_code)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE nps_entry (
        id             VARCHAR2(36) PRIMARY KEY,
        user_id        VARCHAR2(36) NOT NULL,
        account_id     VARCHAR2(36) NOT NULL,
        tier           VARCHAR2(4) NOT NULL,
        type           VARCHAR2(16) NOT NULL,
        contributor    VARCHAR2(8),
        amount         NUMBER(14,2),
        posted_date    DATE NOT NULL,
        nav_date       DATE,
        source         VARCHAR2(10) NOT NULL,
        status         VARCHAR2(10) NOT NULL,
        description    VARCHAR2(300 CHAR),
        transaction_id VARCHAR2(36),
        statement_id   VARCHAR2(36),
        dedupe_key     VARCHAR2(200 CHAR),
        note           VARCHAR2(500 CHAR),
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_nps_entry_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_entry_account FOREIGN KEY (account_id) REFERENCES nps_account(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_entry_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
        CONSTRAINT ck_nps_entry_tier CHECK (tier IN (''I'', ''II'')),
        CONSTRAINT ck_nps_entry_type CHECK (type IN (''CONTRIBUTION'', ''CHARGE'', ''REBALANCE'', ''SWITCH'', ''MIGRATION'', ''WITHDRAWAL'', ''OPENING'', ''ADJUSTMENT'', ''OTHER'')),
        CONSTRAINT ck_nps_entry_contributor CHECK (contributor IN (''SELF'', ''EMPLOYER'')),
        CONSTRAINT ck_nps_entry_source CHECK (source IN (''MANUAL'', ''STATEMENT'', ''EMAIL'', ''BANK'')),
        CONSTRAINT ck_nps_entry_status CHECK (status IN (''CONFIRMED'', ''ESTIMATED'', ''PENDING''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_entry_user_posted ON nps_entry (user_id, posted_date)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_entry_account_posted ON nps_entry (account_id, posted_date)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_entry_txn ON nps_entry (transaction_id)', -955, -1408);
    -- Plain unique index: Oracle ignores rows whose dedupe_key is null.
    run_ddl_swallowing('CREATE UNIQUE INDEX uq_nps_entry_dedupe ON nps_entry (account_id, dedupe_key)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE nps_entry_line (
        id          VARCHAR2(36) PRIMARY KEY,
        entry_id    VARCHAR2(36) NOT NULL,
        scheme_code VARCHAR2(12) NOT NULL,
        units       NUMBER(16,4) NOT NULL,
        nav         NUMBER(12,4),
        amount      NUMBER(14,2),
        CONSTRAINT fk_nps_line_entry FOREIGN KEY (entry_id) REFERENCES nps_entry(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_line_scheme FOREIGN KEY (scheme_code) REFERENCES nps_scheme(scheme_code)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_line_entry ON nps_entry_line (entry_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_nps_line_scheme ON nps_entry_line (scheme_code)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE nps_link_dismissal (
        user_id        VARCHAR2(36) NOT NULL,
        transaction_id VARCHAR2(36) NOT NULL,
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_nps_link_dismissal PRIMARY KEY (user_id, transaction_id),
        CONSTRAINT fk_nps_dismissal_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_nps_dismissal_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_nps_dismissal_txn ON nps_link_dismissal (transaction_id)', -955, -1408);
END;
/
