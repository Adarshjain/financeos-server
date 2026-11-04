-- Index every foreign-key column that had no index of its own.
--
-- Oracle locks the whole child table while a parent row is deleted (or its key updated) when the
-- child's FK column has no leading index. Two users deleting accounts at once then deadlocked on
-- account_suggestions (ORA-00060, seen in E2E); the same held for deleting transactions
-- (gmail_processed_messages), cards, tags, users, Gmail senders and categories.
--
-- Idempotent: CREATE INDEX swallows ORA-00955 (name already used) and ORA-01408 (column already indexed).

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
    run_ddl_swallowing('CREATE INDEX idx_acs_parent_suggestion ON account_suggestions (parent_suggestion_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_acs_parent_account ON account_suggestions (parent_account_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_acs_candidate_account ON account_suggestions (candidate_account_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_acs_resolved_account ON account_suggestions (resolved_account_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_acs_resolved_card ON account_suggestions (resolved_card_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_crule_category ON category_rules (category_key)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_crule_subcategory ON category_rules (subcategory_key)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_cact_target_instrument ON corporate_actions (target_instrument_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_epf_dismissal_user ON epf_link_dismissal (user_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_gpm_transaction ON gmail_processed_messages (transaction_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_gsc_sender ON gmail_sync_cursors (sender_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_rrc_category ON reward_rule_categories (category_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_tsc_instrument ON trade_settlement_classifications (instrument_id)', -955, -1408);
    run_ddl_swallowing('CREATE INDEX idx_txn_category_key ON transactions (category_key)', -955, -1408);
END;
/
