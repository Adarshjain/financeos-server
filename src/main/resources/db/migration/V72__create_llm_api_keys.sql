CREATE TABLE llm_api_keys (
    id             VARCHAR2(36)  NOT NULL,
    user_id        VARCHAR2(36)  NOT NULL,
    provider       VARCHAR2(32)  NOT NULL,
    key_ciphertext VARCHAR2(512) NOT NULL,
    key_last4      VARCHAR2(4)   NOT NULL,
    label          VARCHAR2(64),
    position       NUMBER(5)     NOT NULL,
    status         VARCHAR2(20)  DEFAULT 'ACTIVE' NOT NULL,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_used_at   TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_llm_api_keys PRIMARY KEY (id),
    CONSTRAINT fk_llm_api_keys_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_llm_api_keys_provider CHECK (provider IN ('gemini', 'cerebras', 'groq', 'openrouter')),
    CONSTRAINT chk_llm_api_keys_status CHECK (status IN ('ACTIVE', 'INVALID')),
    CONSTRAINT uq_llm_api_keys_user_prov_pos UNIQUE (user_id, provider, position)
);
CREATE INDEX ix_llm_api_keys_user ON llm_api_keys (user_id);
