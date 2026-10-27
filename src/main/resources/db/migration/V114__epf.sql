-- EPF (Employees' Provident Fund): employers, the ledger entries per employer (contributions, interest,
-- transfers, withdrawals, opening balances), and "not this one" dismissals of withdrawal-to-bank-credit suggestions.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists) and ORA-01408 (column list already indexed).

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
    run_ddl_swallowing('CREATE TABLE epf_employer (
        id               VARCHAR2(36) PRIMARY KEY,
        user_id          VARCHAR2(36) NOT NULL,
        name             VARCHAR2(200 CHAR) NOT NULL,
        member_id        VARCHAR2(40 CHAR),
        establishment_id VARCHAR2(40 CHAR),
        uan              VARCHAR2(12 CHAR),
        joined_on        DATE,
        left_on          DATE,
        created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_epf_employer_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_epf_employer_user ON epf_employer (user_id)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE epf_entry (
        id                  VARCHAR2(36) PRIMARY KEY,
        user_id             VARCHAR2(36) NOT NULL,
        employer_id         VARCHAR2(36) NOT NULL,
        type                VARCHAR2(20) NOT NULL,
        withdrawal_kind     VARCHAR2(20),
        wage_month          DATE,
        credited_on         DATE NOT NULL,
        interest_fy         NUMBER(4),
        employee_amount     NUMBER(19,4) DEFAULT 0 NOT NULL,
        employer_amount     NUMBER(19,4) DEFAULT 0 NOT NULL,
        pension_amount      NUMBER(19,4) DEFAULT 0 NOT NULL,
        tds_amount          NUMBER(19,4),
        counterpart_entry_id VARCHAR2(36),
        note                VARCHAR2(500 CHAR),
        particulars         VARCHAR2(300 CHAR),
        source              VARCHAR2(10) NOT NULL,
        imported_at         TIMESTAMP WITH TIME ZONE,
        transaction_id      VARCHAR2(36),
        created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_epf_entry_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_epf_entry_employer FOREIGN KEY (employer_id) REFERENCES epf_employer(id) ON DELETE CASCADE,
        CONSTRAINT fk_epf_entry_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE SET NULL,
        CONSTRAINT ck_epf_entry_type CHECK (type IN (''CONTRIBUTION'', ''OPENING_BALANCE'', ''INTEREST'', ''TRANSFER_IN'', ''TRANSFER_OUT'', ''WITHDRAWAL'')),
        CONSTRAINT ck_epf_entry_kind CHECK (withdrawal_kind IN (''PARTIAL'', ''FINAL_SETTLEMENT'', ''PENSION'')),
        CONSTRAINT ck_epf_entry_source CHECK (source IN (''MANUAL'', ''PASSBOOK''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_epf_entry_user_credited ON epf_entry (user_id, credited_on)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_epf_entry_employer ON epf_entry (employer_id)', -955, -1408);
    run_ddl_swallowing('CREATE UNIQUE INDEX uq_epf_entry_txn ON epf_entry (transaction_id)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE epf_link_dismissal (
        id             VARCHAR2(36) PRIMARY KEY,
        user_id        VARCHAR2(36) NOT NULL,
        entry_id       VARCHAR2(36) NOT NULL,
        transaction_id VARCHAR2(36) NOT NULL,
        created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_epf_dismissal_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_epf_dismissal_entry FOREIGN KEY (entry_id) REFERENCES epf_entry(id) ON DELETE CASCADE,
        CONSTRAINT fk_epf_dismissal_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
        CONSTRAINT uq_epf_dismissal UNIQUE (entry_id, transaction_id)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_epf_dismissal_txn ON epf_link_dismissal (transaction_id)', -955, -1408);
END;
/
