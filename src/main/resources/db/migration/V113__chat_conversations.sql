-- V113__chat_conversations.sql
-- Persisted "Ask the data" chats: a conversation per user (the newest 10 are kept, enforced in code)
-- and its ordered messages. User content = the question text; assistant content = a JSON payload
-- (answer, clarify, blocks, steps, status ...); draft_result = what happened to a report draft.
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists) and ORA-01408 (column list already indexed).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT 0) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE NOT IN (p_err1, p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE chat_conversation (
        id          VARCHAR2(36) PRIMARY KEY,
        user_id     VARCHAR2(36) NOT NULL,
        title       VARCHAR2(120 CHAR),
        active_run_id VARCHAR2(36 CHAR),
        created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
        updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_chat_conversation_user_upd ON chat_conversation (user_id, updated_at)', -955, -1408);
    run_ddl_swallowing('CREATE TABLE chat_message (
        id               VARCHAR2(36) PRIMARY KEY,
        conversation_id  VARCHAR2(36) NOT NULL,
        seq              NUMBER(10) NOT NULL,
        role             VARCHAR2(10 CHAR) NOT NULL,
        content          CLOB NOT NULL,
        draft_result     CLOB,
        created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_chat_message_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE,
        CONSTRAINT ck_chat_message_role CHECK (role IN (''USER'', ''ASSISTANT'')),
        CONSTRAINT uq_chat_message_conv_seq UNIQUE (conversation_id, seq)
    )', -955);
END;
/
