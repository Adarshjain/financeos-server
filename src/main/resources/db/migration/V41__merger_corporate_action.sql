-- Flyway Migration V41: Merger Corporate Action Support

ALTER TABLE corporate_actions DROP CONSTRAINT chk_corp_actions_type;
ALTER TABLE corporate_actions ADD CONSTRAINT chk_corp_actions_type
    CHECK (type IN ('split', 'bonus', 'demerger', 'merger'));
