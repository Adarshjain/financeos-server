-- V129__account_suggestions.sql
-- Account suggestions from background account detection: one row per (user, bank:kind:last4). Facts are the
-- detector's (bank id, last4, product name, limit bucket, lifecycle, dates, mail count; never mail content). State
-- NEW rows are shown; ADDED, NOT_MINE and BELONGS stay quiet forever; MATCHED means the user already has it.
-- Cascades with the user; account / card / parent-suggestion links are cleared when those rows go.
--
-- Idempotent: swallows ORA-00955 (name already used) and ORA-01408 (column list already indexed).

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
    run_ddl_swallowing('CREATE TABLE account_suggestions (
        id                    VARCHAR2(36) NOT NULL,
        user_id               VARCHAR2(36) NOT NULL,
        suggestion_key        VARCHAR2(80) NOT NULL,
        kind                  VARCHAR2(20) NOT NULL,
        bank                  VARCHAR2(40) NOT NULL,
        last4                 VARCHAR2(4) NOT NULL,
        variant               VARCHAR2(150),
        variant_conf          VARCHAR2(10),
        variant_candidates    VARCHAR2(2000),
        limit_bucket          VARCHAR2(40),
        limit_conf            VARCHAR2(10),
        lifecycle             VARCHAR2(20),
        lifecycle_conf        VARCHAR2(10),
        exists_conf           VARCHAR2(10) NOT NULL,
        band                  VARCHAR2(10) NOT NULL,
        first_seen_on         DATE,
        last_seen_on          DATE,
        evidence_mails        NUMBER(10) DEFAULT 0 NOT NULL,
        parent_account_id     VARCHAR2(36),
        parent_suggestion_id  VARCHAR2(36),
        candidate_account_id  VARCHAR2(36),
        state                 VARCHAR2(10) NOT NULL,
        resolved_account_id   VARCHAR2(36),
        resolved_card_id      VARCHAR2(36),
        notified_at           TIMESTAMP(6) WITH TIME ZONE,
        created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        updated_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,
        CONSTRAINT pk_account_suggestions PRIMARY KEY (id),
        CONSTRAINT fk_acct_sugg_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
        CONSTRAINT fk_acct_sugg_parent_acct FOREIGN KEY (parent_account_id) REFERENCES accounts(id) ON DELETE SET NULL,
        CONSTRAINT fk_acct_sugg_parent_sugg FOREIGN KEY (parent_suggestion_id) REFERENCES account_suggestions(id) ON DELETE SET NULL,
        CONSTRAINT fk_acct_sugg_candidate FOREIGN KEY (candidate_account_id) REFERENCES accounts(id) ON DELETE SET NULL,
        CONSTRAINT fk_acct_sugg_resolved_acct FOREIGN KEY (resolved_account_id) REFERENCES accounts(id) ON DELETE SET NULL,
        CONSTRAINT fk_acct_sugg_resolved_card FOREIGN KEY (resolved_card_id) REFERENCES cards(id) ON DELETE SET NULL,
        CONSTRAINT uq_acct_sugg_user_key UNIQUE (user_id, suggestion_key),
        CONSTRAINT ck_acct_sugg_kind CHECK (kind IN (''BANK_ACCOUNT'', ''CREDIT_CARD'', ''DEBIT_CARD'', ''ADDON_CARD'', ''REPLACEMENT_CARD'')),
        CONSTRAINT ck_acct_sugg_band CHECK (band IN (''FOUND'', ''OLDER'', ''MAYBE'')),
        CONSTRAINT ck_acct_sugg_state CHECK (state IN (''NEW'', ''ADDED'', ''NOT_MINE'', ''BELONGS'', ''MATCHED''))
    )', -955);
END;
/
