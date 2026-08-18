package com.financeos.core.observability;

/**
 * Shared event vocabulary constants across FinanceOS.
 * <p>
 * Naming convention: {@code <domain>.<action>.<outcome>}, lowercase, dot-separated.
 * This class defines the authoritative machine-readable event vocabulary for log searching
 * and metric aggregation. The client tier mirrors this vocabulary (§11.3).
 */
public final class Events {

    private Events() {
        // Utility class
    }

    // System & Lifecycle Events
    public static final String APP_STARTED = "app.started";
    public static final String APP_CONFIG_SUSPECT = "app.config.suspect";

    // HTTP & Security Events
    public static final String HTTP_REQUEST = "http.request";
    public static final String AUTH_DENIED = "auth.denied";
    public static final String REQUEST_FAILED = "request.failed";
    public static final String AUTH_CORS_REJECTED = "auth.cors.rejected";

    // Auth & Session Events
    public static final String AUTH_LOGIN_SUCCEEDED = "auth.login.succeeded";
    public static final String AUTH_LOGIN_FAILED = "auth.login.failed";
    public static final String AUTH_SIGNUP_SUCCEEDED = "auth.signup.succeeded";
    public static final String AUTH_SIGNUP_REJECTED = "auth.signup.rejected";
    public static final String AUTH_SIGNUP_THROTTLED = "auth.signup.throttled";
    public static final String AUTH_SESSION_CREATED = "auth.session.created";
    // Note: JDBC-backed session expiry is unobservable because Spring Session performs a bulk SQL DELETE without publishing per-session events.

    // Google OAuth Events
    public static final String OAUTH_GOOGLE_AUTHORIZE_STARTED = "oauth.google.authorize.started";
    public static final String OAUTH_GOOGLE_CALLBACK_RECEIVED = "oauth.google.callback.received";
    public static final String OAUTH_GOOGLE_TOKEN_EXCHANGED = "oauth.google.token.exchanged";
    public static final String OAUTH_GOOGLE_CALLBACK_FAILED = "oauth.google.callback.failed";

    // Gmail OAuth Events
    public static final String OAUTH_GMAIL_AUTHORIZE_STARTED = "oauth.gmail.authorize.started";
    public static final String OAUTH_GMAIL_CALLBACK_RECEIVED = "oauth.gmail.callback.received";
    public static final String OAUTH_GMAIL_TOKEN_EXCHANGED = "oauth.gmail.token.exchanged";
    public static final String OAUTH_GMAIL_CALLBACK_FAILED = "oauth.gmail.callback.failed";

    // LLM Events
    public static final String LLM_ATTEMPT = "llm.attempt";
    public static final String LLM_CIRCUIT_OPENED = "llm.circuit.opened";
    public static final String LLM_CIRCUIT_CLOSED = "llm.circuit.closed";
    public static final String LLM_CHAIN_EXHAUSTED = "llm.chain.exhausted";

    // Job Events
    public static final String JOB_STARTED = "job.started";
    public static final String JOB_COMPLETED = "job.completed";
    public static final String JOB_FAILED = "job.failed";

    // Ingestion & Parsing Events (Phase 5B)
    public static final String INGEST_FILE_RECEIVED = "ingest.file.received";
    public static final String PARSE_STARTED = "parse.started";
    public static final String PARSE_COMPLETED = "parse.completed";
    public static final String PARSE_ROW_REJECTED = "parse.row.rejected";
    public static final String PARSE_FAILED = "parse.failed";
    public static final String IMPORT_PREVIEW_COMPUTED = "import.preview.computed";
    public static final String IMPORT_COMMIT_COMPLETED = "import.commit.completed";
    public static final String DEDUP_DECISION = "dedup.decision";
    public static final String INSTRUMENT_RESOLVE = "instrument.resolve";

    // Domain Decisions Events (Phase 5B)
    public static final String CATEGORIZE_DECISION = "categorize.decision";
    public static final String REWARD_RECOMPUTE_COMPLETED = "reward.recompute.completed";
    public static final String REWARD_REPORT_VIEWED = "reward.report.viewed";
    public static final String REWARD_RULE_SKIPPED = "reward.rule.skipped";
    public static final String REWARD_RECOMMEND_RANKED = "reward.recommend.ranked";
    public static final String TXN_LINK_CREATED = "txn.link.created";
    public static final String TXN_LINK_REMOVED = "txn.link.removed";
    public static final String LOAN_SCHEDULE_GENERATED = "loan.schedule.generated";
    public static final String LOAN_MATCH_ATTEMPTED = "loan.match.attempted";
    public static final String CA_CREATED = "ca.created";
    // ca.applied is intentionally omitted: corporate actions in FinanceOS are replayed dynamically
    // on read over lots rather than applied once as a discrete database state mutation.

    // Audit Trail Events (Phase 5B)
    public static final String AUDIT_MUTATION = "audit.mutation";

    // Database Events (Phase 5B/6)
    public static final String DB_SLOW_QUERY = "db.slow_query";
}
