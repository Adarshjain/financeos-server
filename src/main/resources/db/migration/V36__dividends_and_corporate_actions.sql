-- Flyway Migration V36: Dividends and Corporate Actions

-- 1. Create user-filtered dividends table
CREATE TABLE dividends (
    id VARCHAR2(36) PRIMARY KEY,
    user_id VARCHAR2(36),
    holding_id VARCHAR2(36) NOT NULL,
    type VARCHAR2(20) NOT NULL,
    amount NUMBER(19, 4) NOT NULL,
    per_unit NUMBER(19, 4),
    tds NUMBER(19, 4),
    ex_date DATE,
    pay_date DATE NOT NULL,
    source VARCHAR2(50) DEFAULT 'manual',
    notes VARCHAR2(4000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dividends_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_dividends_holding FOREIGN KEY (holding_id) REFERENCES holdings(id) ON DELETE CASCADE,
    CONSTRAINT chk_dividends_type CHECK (type IN ('dividend', 'interest', 'other')),
    CONSTRAINT chk_dividends_amount CHECK (amount >= 0)
);

CREATE INDEX idx_dividends_holding ON dividends (holding_id);
CREATE INDEX idx_dividends_user ON dividends (user_id);
CREATE INDEX idx_dividends_pay_date ON dividends (pay_date);

-- 2. Create global corporate_actions table (market facts, no user_id)
CREATE TABLE corporate_actions (
    id VARCHAR2(36) PRIMARY KEY,
    instrument_id VARCHAR2(36) NOT NULL,
    type VARCHAR2(20) NOT NULL,
    ratio_from NUMBER(10) NOT NULL,
    ratio_to NUMBER(10) NOT NULL,
    ex_date DATE NOT NULL,
    notes VARCHAR2(4000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_corp_actions_inst FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE,
    CONSTRAINT chk_corp_actions_type CHECK (type IN ('split', 'bonus')),
    CONSTRAINT chk_corp_actions_ratio_from CHECK (ratio_from > 0),
    CONSTRAINT chk_corp_actions_ratio_to CHECK (ratio_to > 0)
);

CREATE INDEX idx_corp_actions_inst_date ON corporate_actions (instrument_id, ex_date);
