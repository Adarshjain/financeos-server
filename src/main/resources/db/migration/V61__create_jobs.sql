CREATE TABLE jobs (
    id               VARCHAR2(36)  NOT NULL,
    user_id          VARCHAR2(36),            -- NULL only for system/cron-global jobs
    type             VARCHAR2(40)  NOT NULL,
    status           VARCHAR2(20)  NOT NULL,  -- PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED
    trigger_source   VARCHAR2(10)  NOT NULL,  -- USER|CRON
    payload          CLOB,
    result           CLOB,
    error_code       VARCHAR2(64),
    error_message    VARCHAR2(2000),
    progress_current NUMBER(10),
    progress_total   NUMBER(10),
    progress_note    VARCHAR2(255),
    cancel_requested NUMBER(1) DEFAULT 0 NOT NULL,
    attempt          NUMBER(5) DEFAULT 0 NOT NULL,
    dedup_key        VARCHAR2(200),
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at       TIMESTAMP(6) WITH TIME ZONE,
    finished_at      TIMESTAMP(6) WITH TIME ZONE,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_jobs PRIMARY KEY (id),
    CONSTRAINT fk_jobs_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_jobs_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED'))
);
CREATE INDEX ix_jobs_status_created ON jobs (status, created_at);
CREATE INDEX ix_jobs_user_created   ON jobs (user_id, created_at DESC);

CREATE TABLE job_artifacts (
    id           VARCHAR2(36) NOT NULL,
    job_id       VARCHAR2(36) NOT NULL,
    filename     VARCHAR2(255) NOT NULL,
    content_type VARCHAR2(100),
    size_bytes   NUMBER(19) NOT NULL,
    data         BLOB NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_job_artifacts PRIMARY KEY (id),
    CONSTRAINT fk_job_artifacts_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);
CREATE INDEX ix_job_artifacts_job ON job_artifacts (job_id);
