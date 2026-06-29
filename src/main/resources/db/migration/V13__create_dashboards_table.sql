-- Composable dashboards: a grid of report widgets. Widgets are stored as JSON (CLOB),
-- mirroring the JSON-in-CLOB pattern used by reports.definition.
CREATE TABLE dashboards (
    id          VARCHAR2(36) PRIMARY KEY,
    user_id     VARCHAR2(36),
    name        VARCHAR2(255) NOT NULL,
    description VARCHAR2(4000),
    widgets     CLOB NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dashboards_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_dashboards_widgets_json CHECK (widgets IS JSON)
);

CREATE INDEX idx_dashboards_user_id ON dashboards(user_id);
