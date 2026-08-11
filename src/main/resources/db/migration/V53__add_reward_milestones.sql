-- Milestone benefits: spend/transaction-count thresholds per window that pay a
-- fixed cash value (voucher/bonus) or just track progress (fee-waiver tracker).
-- Eligibility (include/exclude category+MCC lists) is stored as JSON text, the
-- same approach the reports module uses for its definition column.
CREATE TABLE reward_milestones (
    id              VARCHAR2(36) PRIMARY KEY,
    user_id         VARCHAR2(36) NOT NULL,
    account_id      VARCHAR2(36) NOT NULL,
    name            VARCHAR2(200) NOT NULL,
    window_type     VARCHAR2(20) NOT NULL,
    basis           VARCHAR2(20) NOT NULL,
    threshold       NUMBER(19,4) NOT NULL,
    min_txn_amount  NUMBER(19,4),
    payout_type     VARCHAR2(20) NOT NULL,
    payout_value    NUMBER(19,4),
    eligibility     CLOB,
    active_from     DATE,
    active_to       DATE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_rm_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_rm_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_rm_window CHECK (window_type IN ('CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR')),
    CONSTRAINT chk_rm_basis CHECK (basis IN ('SPEND','TXN_COUNT')),
    CONSTRAINT chk_rm_payout CHECK (payout_type IN ('CASH_VALUE','INFO_TRACKER')),
    CONSTRAINT chk_rm_threshold CHECK (threshold > 0),
    CONSTRAINT chk_rm_active_range CHECK (active_to IS NULL OR active_from IS NULL OR active_from < active_to)
);
CREATE INDEX idx_rm_user ON reward_milestones(user_id);
CREATE INDEX idx_rm_account ON reward_milestones(account_id);
