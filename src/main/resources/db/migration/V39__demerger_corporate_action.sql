-- Flyway Migration V39: Demerger Corporate Action and Instrument Aliases

-- 1. Create instrument_aliases table
CREATE TABLE instrument_aliases (
    id VARCHAR2(36) PRIMARY KEY,
    instrument_id VARCHAR2(36) NOT NULL,
    old_symbol VARCHAR2(50),
    old_name VARCHAR2(255),
    source VARCHAR2(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inst_aliases_inst FOREIGN KEY (instrument_id) REFERENCES instruments(id) ON DELETE CASCADE
);

CREATE INDEX idx_inst_aliases_old_sym ON instrument_aliases (old_symbol);
CREATE INDEX idx_inst_aliases_inst ON instrument_aliases (instrument_id);

-- 2. Extend corporate_actions for demerger
ALTER TABLE corporate_actions ADD target_instrument_id VARCHAR2(36);
ALTER TABLE corporate_actions ADD cost_allocation_pct NUMBER(7,4);
ALTER TABLE corporate_actions ADD CONSTRAINT fk_corp_actions_target
    FOREIGN KEY (target_instrument_id) REFERENCES instruments(id);

-- Replace the type check to allow 'demerger'
ALTER TABLE corporate_actions DROP CONSTRAINT chk_corp_actions_type;
ALTER TABLE corporate_actions ADD CONSTRAINT chk_corp_actions_type
    CHECK (type IN ('split', 'bonus', 'demerger'));
ALTER TABLE corporate_actions ADD CONSTRAINT chk_corp_actions_cost_pct
    CHECK (cost_allocation_pct IS NULL OR (cost_allocation_pct >= 0 AND cost_allocation_pct <= 100));
