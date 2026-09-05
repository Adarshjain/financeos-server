-- Flyway Migration V85: Account Identifier Aliases

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
    -- ORA-00955: name is already used by an existing object
    run_ddl_swallowing('CREATE TABLE account_identifiers (
        id          VARCHAR2(36) PRIMARY KEY,
        user_id     VARCHAR2(36) NOT NULL,
        account_id  VARCHAR2(36) NOT NULL,
        value       VARCHAR2(32) NOT NULL,
        kind        VARCHAR2(20) NOT NULL,
        created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_ai_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_ai_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
        CONSTRAINT chk_ai_kind CHECK (kind IN (''CUSTOMER_ID'', ''ACCOUNT_NUMBER'', ''CRN'', ''OTHER'')),
        CONSTRAINT uq_ai_user_value UNIQUE (user_id, value)
    )', -955);

    -- Index on account_id (ORA-00955: name already used, ORA-01408: such column list already indexed)
    run_ddl_swallowing('CREATE INDEX idx_ai_account ON account_identifiers(account_id)', -955, -1408);
END;
/
