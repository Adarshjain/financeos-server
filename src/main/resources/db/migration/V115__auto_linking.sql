-- V115__auto_linking.sql
-- Auto-linking: link_suggestions (a transaction that has several possible partners, for the user to
-- confirm), auto_link_rejections (pairings the user unlinked or dismissed, never proposed again) and
-- users.auto_link_backfilled_at (one-time history backfill marker; NULL = backfill still to run).
--
-- Idempotent: the DDL swallows ORA-00955 (object already exists), ORA-01408 (column list already
-- indexed) and ORA-01430 (column already in the table).

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
    run_ddl_swallowing('CREATE TABLE link_suggestions (
        id              VARCHAR2(36) PRIMARY KEY,
        user_id         VARCHAR2(36) NOT NULL,
        kind            VARCHAR2(20) NOT NULL,
        transaction_id  VARCHAR2(36) NOT NULL,
        candidate_ids   VARCHAR2(4000) NOT NULL,
        created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT fk_link_sugg_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_link_sugg_txn FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE CASCADE,
        CONSTRAINT uq_link_sugg_user_kind_txn UNIQUE (user_id, kind, transaction_id)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_link_sugg_user ON link_suggestions (user_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_link_sugg_txn ON link_suggestions (transaction_id)', -955, -1408);

    run_ddl_swallowing('CREATE TABLE auto_link_rejections (
        user_id         VARCHAR2(36) NOT NULL,
        kind            VARCHAR2(20) NOT NULL,
        transaction_id  VARCHAR2(36) NOT NULL,
        other_ref       VARCHAR2(36) NOT NULL,
        created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_auto_link_rejections PRIMARY KEY (user_id, kind, transaction_id, other_ref),
        CONSTRAINT fk_auto_link_rej_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    )', -955);

    run_ddl_swallowing('ALTER TABLE users ADD auto_link_backfilled_at TIMESTAMP WITH TIME ZONE', -1430);
END;
/
