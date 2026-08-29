CREATE TABLE llm_task_prefs (
    id         VARCHAR2(36) NOT NULL,
    user_id    VARCHAR2(36) NOT NULL,
    task_group VARCHAR2(16) NOT NULL,
    position   NUMBER(5)    NOT NULL,
    provider   VARCHAR2(32) NOT NULL,
    model      VARCHAR2(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_llm_task_prefs PRIMARY KEY (id),
    CONSTRAINT fk_llm_task_prefs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_llm_task_prefs_group CHECK (task_group IN ('CHAT','DEFAULT')),
    CONSTRAINT uq_llm_task_prefs UNIQUE (user_id, task_group, position)
);
CREATE INDEX ix_llm_task_prefs_user ON llm_task_prefs (user_id, task_group);

ALTER TABLE llm_api_keys DROP CONSTRAINT chk_llm_api_keys_provider;
