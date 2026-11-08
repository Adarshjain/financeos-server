-- perf_rollup: hourly per-op latency rollups (count, total, p50/p95/p99/max, errors) flushed by each node
-- (PerfRollupAggregator), so a regression is comparable across deploys. Global: no user_id, user ids never go here.
-- One row per (op, role, version, hour_start); a restart inside the same hour merges into the same row.
--
-- Idempotent: the table, its unique constraint and the index are each created only when missing
-- (ORA-00955 name already used, ORA-02261 unique key already exists, ORA-01408 index column list already indexed).

DECLARE
    PROCEDURE run_ddl_swallowing(p_sql VARCHAR2, p_err1 NUMBER, p_err2 NUMBER DEFAULT NULL) IS
    BEGIN
        EXECUTE IMMEDIATE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLCODE != p_err1 AND (p_err2 IS NULL OR SQLCODE != p_err2) THEN
                RAISE;
            END IF;
    END;
BEGIN
    run_ddl_swallowing('CREATE TABLE perf_rollup (
        id          VARCHAR2(36) NOT NULL,
        op          VARCHAR2(200) NOT NULL,
        role        VARCHAR2(20) NOT NULL,
        version     VARCHAR2(40) NOT NULL,
        hour_start  TIMESTAMP NOT NULL,
        count       NUMBER(19) NOT NULL,
        total_ms    NUMBER(19) NOT NULL,
        p50_ms      NUMBER(19) NOT NULL,
        p95_ms      NUMBER(19) NOT NULL,
        p99_ms      NUMBER(19) NOT NULL,
        max_ms      NUMBER(19) NOT NULL,
        error_count NUMBER(19) DEFAULT 0 NOT NULL,
        CONSTRAINT pk_perf_rollup PRIMARY KEY (id),
        CONSTRAINT uq_perf_rollup UNIQUE (op, role, version, hour_start)
    )', -955);
    run_ddl_swallowing('CREATE INDEX idx_perf_rollup_hour ON perf_rollup (hour_start)', -955, -1408);
END;
/
