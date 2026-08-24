-- ojdbc 23.x cannot read a plain TIMESTAMP column as OffsetDateTime (ORA-18716: "not in any
-- time zone"), which is how Hibernate 6.3+ hydrates java.time.Instant fields (HHH-17552).
-- The codebase convention for Instant is TIMESTAMP WITH TIME ZONE (see V61 jobs); V16, V26,
-- V44, V65, V66 and V67 deviated. This broke every Gmail sync since the V65-V69 deploy
-- (gmail_sync_cursors is hydrated on each run) and left the same landmine under
-- gmail_processed_messages, category_rules and fno_trades.
--
-- Convert every Instant-mapped plain TIMESTAMP column to TIMESTAMP WITH TIME ZONE,
-- interpreting stored values as UTC (they were written by a UTC JVM or by
-- CURRENT_TIMESTAMP in a UTC session; verified against cron times on the live data).
--
-- Oracle cannot ALTER a non-empty column between datetime families, so each column is
-- converted via add-copy-drop-rename. DDL auto-commits, so every step is guarded to make
-- the migration re-runnable from any interruption point.
ALTER SESSION DISABLE PARALLEL DML;

DECLARE
    TYPE t_list IS TABLE OF VARCHAR2(128);
    tabs t_list := t_list(
        'GMAIL_SYNC_CURSORS', 'GMAIL_SYNC_CURSORS', 'GMAIL_SYNC_CURSORS', 'GMAIL_SYNC_CURSORS',
        'GMAIL_BACKFILL_DEMAND',
        'GMAIL_PROCESSED_MESSAGES', 'GMAIL_PROCESSED_MESSAGES', 'GMAIL_PROCESSED_MESSAGES', 'GMAIL_PROCESSED_MESSAGES',
        'CATEGORY_RULES', 'CATEGORY_RULES', 'CATEGORY_RULES',
        'FNO_TRADES'
    );
    cols t_list := t_list(
        'LAST_LISTED_AT', 'EARLIEST_COVERED_AT', 'CREATED_AT', 'UPDATED_AT',
        'UPDATED_AT',
        'PROCESSED_AT', 'INTERNAL_DATE', 'NEXT_RETRY_AT', 'DISCOVERED_AT',
        'LAST_APPLIED_AT', 'CREATED_AT', 'UPDATED_AT',
        'CREATED_AT'
    );
    -- Columns whose original DDL carried DEFAULT CURRENT_TIMESTAMP
    defaulted t_list := t_list(
        'GMAIL_SYNC_CURSORS.CREATED_AT', 'GMAIL_SYNC_CURSORS.UPDATED_AT',
        'GMAIL_BACKFILL_DEMAND.UPDATED_AT',
        'CATEGORY_RULES.CREATED_AT', 'CATEGORY_RULES.UPDATED_AT',
        'FNO_TRADES.CREATED_AT'
    );
    -- Columns whose original DDL carried NOT NULL
    required t_list := t_list(
        'GMAIL_SYNC_CURSORS.LAST_LISTED_AT', 'GMAIL_SYNC_CURSORS.EARLIEST_COVERED_AT',
        'GMAIL_SYNC_CURSORS.CREATED_AT', 'GMAIL_SYNC_CURSORS.UPDATED_AT',
        'GMAIL_BACKFILL_DEMAND.UPDATED_AT',
        'FNO_TRADES.CREATED_AT'
    );
    plain_cnt NUMBER;
    col_cnt   NUMBER;
    tmp_cnt   NUMBER;
    is_nullable VARCHAR2(1);
    key VARCHAR2(200);

    FUNCTION in_list(l t_list, k VARCHAR2) RETURN BOOLEAN IS
    BEGIN
        FOR j IN 1 .. l.COUNT LOOP
            IF l(j) = k THEN RETURN TRUE; END IF;
        END LOOP;
        RETURN FALSE;
    END;
BEGIN
    FOR i IN 1 .. tabs.COUNT LOOP
        key := tabs(i) || '.' || cols(i);

        SELECT COUNT(*) INTO plain_cnt FROM user_tab_columns
        WHERE table_name = tabs(i) AND column_name = cols(i)
          AND data_type LIKE 'TIMESTAMP%' AND data_type NOT LIKE '%TIME ZONE%';
        SELECT COUNT(*) INTO col_cnt FROM user_tab_columns
        WHERE table_name = tabs(i) AND column_name = cols(i);
        SELECT COUNT(*) INTO tmp_cnt FROM user_tab_columns
        WHERE table_name = tabs(i) AND column_name = cols(i) || '_TZ';

        IF plain_cnt = 1 THEN
            -- Normal path (also recovers a run interrupted before the DROP).
            IF tmp_cnt = 1 THEN
                EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' DROP COLUMN ' || cols(i) || '_TZ';
            END IF;
            EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' ADD (' || cols(i) || '_TZ TIMESTAMP WITH TIME ZONE)';
            EXECUTE IMMEDIATE 'UPDATE ' || tabs(i) || ' SET ' || cols(i) || '_TZ = FROM_TZ(' || cols(i) || ', ''UTC'')';
            EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' DROP COLUMN ' || cols(i);
            EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' RENAME COLUMN ' || cols(i) || '_TZ TO ' || cols(i);
        ELSIF col_cnt = 0 AND tmp_cnt = 1 THEN
            -- Recovers a run interrupted between DROP and RENAME.
            EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' RENAME COLUMN ' || cols(i) || '_TZ TO ' || cols(i);
        END IF;

        IF in_list(defaulted, key) THEN
            EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' MODIFY ' || cols(i) || ' DEFAULT CURRENT_TIMESTAMP';
        END IF;
        IF in_list(required, key) THEN
            SELECT nullable INTO is_nullable FROM user_tab_columns
            WHERE table_name = tabs(i) AND column_name = cols(i);
            IF is_nullable = 'Y' THEN
                EXECUTE IMMEDIATE 'ALTER TABLE ' || tabs(i) || ' MODIFY ' || cols(i) || ' NOT NULL';
            END IF;
        END IF;
    END LOOP;
END;
/

-- Dropping discovered_at silently dropped the composite index that includes it; recreate.
DECLARE
    idx_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO idx_count FROM user_indexes WHERE index_name = 'IDX_GPM_CONN_STAT_DISC';
    IF idx_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_gpm_conn_stat_disc ON gmail_processed_messages(connection_id, status, discovered_at)';
    END IF;
END;
/
