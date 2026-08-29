-- Make every user_id foreign key ON DELETE CASCADE, so deleting a user row removes
-- everything of theirs, and close the one tenancy column that has no foreign key at all.
--
-- Oracle DDL auto-commits, so a failure partway through cannot roll back: this migration
-- has to be safe to re-run over a half-applied schema. Every section therefore checks the
-- data dictionary first rather than assuming a clean slate.

-- 1. Index every user_id that lacks one. An unindexed FK column makes Oracle take a share
--    lock on the child during parent DML and full-scan it to find cascade victims.
DECLARE
    PROCEDURE ensure_user_id_index(p_table VARCHAR2, p_index VARCHAR2) IS
        v_count NUMBER;
    BEGIN
        -- Any index whose LEADING column is user_id already serves the foreign key,
        -- including a system-named one behind a primary or unique constraint. A blind
        -- CREATE INDEX here fails with ORA-01408 on gmail_backfill_demand, whose primary
        -- key is on user_id — which is why this checks columns rather than index names.
        SELECT COUNT(*) INTO v_count
        FROM   user_ind_columns
        WHERE  table_name = p_table
          AND  column_name = 'USER_ID'
          AND  column_position = 1;

        IF v_count = 0 THEN
            EXECUTE IMMEDIATE 'CREATE INDEX ' || p_index || ' ON ' || p_table || ' (user_id)';
        END IF;
    END;
BEGIN
    ensure_user_id_index('ACCOUNT_BANK_DETAILS',             'ix_abd_user');
    ensure_user_id_index('ACCOUNT_CREDIT_CARD_DETAILS',      'ix_accd_user');
    ensure_user_id_index('ACCOUNT_BROKER_DETAILS',           'ix_abrd_user');
    ensure_user_id_index('STATEMENT_CREDIT_CARD_DETAILS',    'ix_sccd_user');
    ensure_user_id_index('TRADE_SETTLEMENT_CLASSIFICATIONS', 'ix_tsc_user');
    ensure_user_id_index('GMAIL_SYNC_CURSORS',               'ix_gsc_user');
    ensure_user_id_index('GMAIL_BACKFILL_DEMAND',            'ix_gbd_user');
END;
/

-- 2. fno_trades carries a user_id with no foreign key behind it at all (V44 gave it only
--    an index), so it is invisible to any cascade from users. NOVALIDATE tolerates a
--    pre-existing orphan while still enforcing every future insert.
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM   user_constraints
    WHERE  table_name = 'FNO_TRADES' AND constraint_name = 'FK_FNO_TRADES_USER';

    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE fno_trades ADD CONSTRAINT fk_fno_trades_user '
                       || 'FOREIGN KEY (user_id) REFERENCES users(id) '
                       || 'ON DELETE CASCADE ENABLE NOVALIDATE';
    END IF;
END;
/

-- 3. fk_fno_broker has no delete rule while every sibling FK to accounts(id) cascades.
--    That is why deleting a broker account holding F&O trades fails today with ORA-02292.
DECLARE
    v_rule user_constraints.delete_rule%TYPE;
BEGIN
    SELECT delete_rule INTO v_rule
    FROM   user_constraints
    WHERE  table_name = 'FNO_TRADES' AND constraint_name = 'FK_FNO_BROKER';

    IF v_rule <> 'CASCADE' THEN
        EXECUTE IMMEDIATE 'ALTER TABLE fno_trades DROP CONSTRAINT fk_fno_broker';
        EXECUTE IMMEDIATE 'ALTER TABLE fno_trades ADD CONSTRAINT fk_fno_broker '
                       || 'FOREIGN KEY (broker_account_id) REFERENCES accounts(id) ON DELETE CASCADE';
    END IF;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        -- A previous run dropped it and died before the re-add.
        EXECUTE IMMEDIATE 'ALTER TABLE fno_trades ADD CONSTRAINT fk_fno_broker '
                       || 'FOREIGN KEY (broker_account_id) REFERENCES accounts(id) ON DELETE CASCADE';
END;
/

-- 4. Rebuild every foreign key pointing at users(id) as ON DELETE CASCADE.
--    Oracle has no ALTER CONSTRAINT ... ON DELETE, so each must be dropped and re-added.
--    Driving this off the data dictionary rather than a hand-written list of names means
--    it cannot miss one, it self-corrects if the live schema has drifted from these
--    migrations, and it skips constraints already converted by an earlier partial run.
DECLARE
    v_users_pk  VARCHAR2(128);
    v_cols      VARCHAR2(4000);
BEGIN
    -- V1 declared the key inline (`id VARCHAR2(36) PRIMARY KEY`), so its name is a
    -- generated SYS_Cnnnn and cannot be hardcoded.
    SELECT constraint_name INTO v_users_pk
    FROM   user_constraints
    WHERE  table_name = 'USERS' AND constraint_type = 'P';

    FOR c IN (
        SELECT constraint_name, table_name
        FROM   user_constraints
        WHERE  constraint_type = 'R'
          AND  r_constraint_name = v_users_pk
          AND  delete_rule <> 'CASCADE'
    ) LOOP
        SELECT LISTAGG('"' || column_name || '"', ',') WITHIN GROUP (ORDER BY position)
        INTO   v_cols
        FROM   user_cons_columns
        WHERE  constraint_name = c.constraint_name;

        EXECUTE IMMEDIATE 'ALTER TABLE "' || c.table_name ||
                          '" DROP CONSTRAINT "' || c.constraint_name || '"';
        EXECUTE IMMEDIATE 'ALTER TABLE "' || c.table_name ||
                          '" ADD CONSTRAINT "' || c.constraint_name ||
                          '" FOREIGN KEY (' || v_cols || ') REFERENCES users(id) ' ||
                          'ON DELETE CASCADE ENABLE NOVALIDATE';
    END LOOP;
END;
/
