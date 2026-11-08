-- bill_payments: which payment pays which credit-card bill. One row per payment (a bill may have several part
-- payments): the card-side credit, the bank debit it came from (when known), whether the app created that bank
-- debit (undo may then delete it), the origin (AUTO = matched by the bill-payment matcher, USER = "Mark paid"),
-- and the CC_PAYMENT link joining the two (null for a card-side-only payment). Additive: the previous JAR ignores it.
--
-- Idempotent: the table is created only when missing (ORA-00955), the indexes only when missing (ORA-00955 /
-- ORA-01408).

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
    run_ddl_swallowing('CREATE TABLE bill_payments (
        id                VARCHAR2(36) PRIMARY KEY,
        user_id           VARCHAR2(36) NOT NULL,
        statement_id      VARCHAR2(36) NOT NULL,
        card_txn_id       VARCHAR2(36) NOT NULL,
        bank_txn_id       VARCHAR2(36),
        bank_txn_created  NUMBER(1) DEFAULT 0 NOT NULL,
        origin            VARCHAR2(10) NOT NULL,
        link_id           VARCHAR2(36),
        created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_bill_pay_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_bill_pay_stmt FOREIGN KEY (statement_id) REFERENCES statements(id) ON DELETE CASCADE,
        CONSTRAINT fk_bill_pay_card FOREIGN KEY (card_txn_id) REFERENCES transactions(id) ON DELETE CASCADE,
        CONSTRAINT fk_bill_pay_bank FOREIGN KEY (bank_txn_id) REFERENCES transactions(id) ON DELETE SET NULL,
        CONSTRAINT ck_bill_pay_origin CHECK (origin IN (''AUTO'', ''USER''))
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_bill_pay_stmt ON bill_payments (statement_id)', -955, -1408);
    run_ddl_swallowing('CREATE UNIQUE INDEX idx_bill_pay_card ON bill_payments (card_txn_id)', -955, -1408);
    run_ddl_swallowing('CREATE UNIQUE INDEX idx_bill_pay_bank ON bill_payments (bank_txn_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_bill_pay_user ON bill_payments (user_id)', -955, -1408);
END;
/
