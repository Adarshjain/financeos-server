-- Reports module: user-defined dynamic reports (KPI / Chart / Table)
-- The type-specific definition is stored as JSON in a CLOB, mirroring the
-- existing JSON-in-CLOB pattern used for transaction metadata.
CREATE TABLE reports (
    id          VARCHAR2(36) PRIMARY KEY,
    user_id     VARCHAR2(36),
    name        VARCHAR2(255) NOT NULL,
    type        VARCHAR2(20)  NOT NULL,
    datasource  VARCHAR2(50)  NOT NULL,
    definition  CLOB          NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_reports_type CHECK (type IN ('KPI', 'CHART', 'TABLE')),
    CONSTRAINT chk_reports_definition_json CHECK (definition IS JSON)
);

CREATE INDEX idx_reports_user_id ON reports(user_id);
CREATE INDEX idx_reports_type ON reports(type);
