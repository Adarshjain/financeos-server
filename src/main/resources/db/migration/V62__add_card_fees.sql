CREATE TABLE card_fee_terms (
    id                     VARCHAR2(36) PRIMARY KEY,
    user_id                VARCHAR2(36) NOT NULL,
    account_id             VARCHAR2(36) NOT NULL,
    kind                   VARCHAR2(20) NOT NULL,
    effective_from         DATE NOT NULL,
    amount                 NUMBER(19,4),
    gst_rate               NUMBER(5,2),
    waiver_spend_threshold NUMBER(19,4),
    waiver_basis           VARCHAR2(20),
    note                   VARCHAR2(500),
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_cft_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT fk_cft_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT uq_cft_slot    UNIQUE (account_id, kind, effective_from),
    CONSTRAINT chk_cft_kind   CHECK (kind IN ('LTF','ANNUAL_FEE','JOINING_FEE')),
    CONSTRAINT chk_cft_basis  CHECK (waiver_basis IS NULL OR waiver_basis IN ('PRECEDING_FEE_YEAR','SAME_FEE_YEAR')),
    CONSTRAINT chk_cft_ltf    CHECK (kind <> 'LTF' OR (amount IS NULL AND waiver_spend_threshold IS NULL)),
    CONSTRAINT chk_cft_amount CHECK (kind = 'LTF' OR amount > 0),
    CONSTRAINT chk_cft_gst    CHECK (gst_rate IS NULL OR (gst_rate >= 0 AND gst_rate <= 100)),
    CONSTRAINT chk_cft_waiver CHECK (waiver_spend_threshold IS NULL
                                     OR (kind = 'ANNUAL_FEE' AND waiver_basis IS NOT NULL
                                         AND waiver_spend_threshold > 0))
);
CREATE INDEX idx_cft_user    ON card_fee_terms(user_id);
CREATE INDEX idx_cft_account ON card_fee_terms(account_id, effective_from);

CREATE UNIQUE INDEX uq_cft_recurring ON card_fee_terms (
    CASE WHEN kind <> 'JOINING_FEE' THEN account_id     END,
    CASE WHEN kind <> 'JOINING_FEE' THEN effective_from END);

CREATE TABLE card_fee_charges (
    id              VARCHAR2(36) PRIMARY KEY,
    user_id         VARCHAR2(36) NOT NULL,
    account_id      VARCHAR2(36) NOT NULL,
    kind            VARCHAR2(20) NOT NULL,
    fee_year_start  DATE NOT NULL,
    waived          NUMBER(1),
    override_amount NUMBER(19,4),
    note            VARCHAR2(500),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_cfc_user    FOREIGN KEY (user_id)    REFERENCES users(id),
    CONSTRAINT fk_cfc_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT uq_cfc_slot    UNIQUE (account_id, kind, fee_year_start),
    CONSTRAINT chk_cfc_kind   CHECK (kind IN ('LTF','ANNUAL_FEE','JOINING_FEE')),
    CONSTRAINT chk_cfc_waived CHECK (waived IS NULL OR waived IN (0,1)),
    CONSTRAINT chk_cfc_amount CHECK (override_amount IS NULL OR override_amount >= 0)
);
CREATE INDEX idx_cfc_user    ON card_fee_charges(user_id);
CREATE INDEX idx_cfc_account ON card_fee_charges(account_id);

CREATE TABLE card_fee_charge_transactions (
    charge_id      VARCHAR2(36) NOT NULL,
    transaction_id VARCHAR2(36) NOT NULL,
    CONSTRAINT pk_cfct PRIMARY KEY (charge_id, transaction_id),
    CONSTRAINT fk_cfct_charge FOREIGN KEY (charge_id)      REFERENCES card_fee_charges(id) ON DELETE CASCADE,
    CONSTRAINT fk_cfct_txn    FOREIGN KEY (transaction_id) REFERENCES transactions(id)     ON DELETE CASCADE,
    -- Also serves as the lookup index for transaction_id: Oracle backs a UNIQUE
    -- constraint with an index, and rejects (ORA-01408) a second index on the
    -- identical column list, so no separate idx_cfct_txn may be created here.
    CONSTRAINT uq_cfct_txn    UNIQUE (transaction_id)
);
