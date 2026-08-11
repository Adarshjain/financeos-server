-- Shared cap buckets: several rules drain ONE ceiling (Axis-ACE shape: 5% and 4%
-- categories share a single ₹500/cycle cap). A rule referencing a bucket uses the
-- bucket's cap+window instead of its own period cap.
CREATE TABLE reward_cap_buckets (
    id          VARCHAR2(36) PRIMARY KEY,
    user_id     VARCHAR2(36) NOT NULL,
    account_id  VARCHAR2(36) NOT NULL,
    name        VARCHAR2(200) NOT NULL,
    cap         NUMBER(19,4) NOT NULL,
    window_type VARCHAR2(20) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_rcb_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_rcb_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT chk_rcb_window CHECK (window_type IN ('DAY','CALENDAR_MONTH','STATEMENT_CYCLE','QUARTER','CALENDAR_YEAR')),
    CONSTRAINT chk_rcb_cap CHECK (cap > 0)
);
CREATE INDEX idx_rcb_user ON reward_cap_buckets(user_id);
CREATE INDEX idx_rcb_account ON reward_cap_buckets(account_id);

-- SET NULL so an account delete can cascade buckets away before rules.
ALTER TABLE reward_rules ADD cap_bucket_id VARCHAR2(36);
ALTER TABLE reward_rules ADD CONSTRAINT fk_rr_cap_bucket FOREIGN KEY (cap_bucket_id) REFERENCES reward_cap_buckets(id) ON DELETE SET NULL;
CREATE INDEX idx_rr_cap_bucket ON reward_rules(cap_bucket_id);
