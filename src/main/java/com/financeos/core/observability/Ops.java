package com.financeos.core.observability;

import java.util.List;
import java.util.Locale;

/**
 * Every {@link Timing} op name in one place, so the metric series budget can be checked by a test.
 * Families with a variable part (job type, Gmail call, LLM provider) are built by the methods below.
 */
public final class Ops {

    private Ops() {
    }

    public static final String PRICE_AMFI = "ext.price.amfi";
    public static final String PRICE_NPS = "ext.price.nps";
    public static final String PRICE_YAHOO = "ext.price.yahoo";
    public static final String PRICE_YAHOO_SEARCH = "ext.price.yahoo_search";
    public static final String PRICE_MFAPI = "ext.price.mfapi";

    public static final String GOOGLE_TOKEN_EXCHANGE = "ext.google.token_exchange";
    public static final String CORP_ACTIONS_NSE = "ext.corpactions.nse";

    public static final List<String> FIXED = List.of(PRICE_AMFI, PRICE_NPS, PRICE_YAHOO, PRICE_YAHOO_SEARCH, PRICE_MFAPI,
            GOOGLE_TOKEN_EXCHANGE, CORP_ACTIONS_NSE);

    // ---- stage ops: no histogram buckets (see TimingConfig), only count/sum/max per outcome
    public static final String DETECTOR_CLUSTER_ROLES = "stage.detector.cluster_roles";
    public static final String DETECTOR_OCCURRENCES = "stage.detector.occurrences";
    public static final String DETECTOR_SLOT_CLASSES = "stage.detector.slot_classes";
    public static final String DETECTOR_STATEMENT_TIE = "stage.detector.statement_tie";
    public static final String DETECTOR_EVIDENCE = "stage.detector.evidence";
    public static final String DETECTOR_ENTITIES = "stage.detector.entities";
    public static final String DETECTOR_VARIANTS = "stage.detector.variants";
    public static final String DETECTOR_LIFECYCLE = "stage.detector.lifecycle";
    public static final String DETECTOR_LIMITS = "stage.detector.limits";
    public static final String DETECTOR_FINISH = "stage.detector.finish";
    public static final String ACCOUNTDETECT_LOAD_STORED = "stage.accountdetect.load_stored";
    public static final String ACCOUNTDETECT_STATEMENTS_FP = "stage.accountdetect.statements_fp";
    public static final String ACCOUNTDETECT_LOAD_WRITTEN = "stage.accountdetect.load_written";
    public static final String ACCOUNTDETECT_DB_STATEMENTS = "stage.accountdetect.db_statements";
    public static final String ACCOUNTDETECT_SINK = "stage.accountdetect.sink";
    public static final String ACCOUNTDETECT_LIST = "stage.accountdetect.list";
    public static final String ACCOUNTDETECT_METADATA = "stage.accountdetect.metadata";
    public static final String ACCOUNTDETECT_CLUSTER_SELECT = "stage.accountdetect.cluster_select";
    public static final String ACCOUNTDETECT_FULL_BODIES = "stage.accountdetect.full_bodies";
    public static final String ACCOUNTDETECT_MAIL_PDFS = "stage.accountdetect.mail_pdfs";
    public static final String ACCOUNTDETECT_DERIVE = "stage.accountdetect.derive";
    public static final String ACCOUNTDETECT_DETECT_FULL = "stage.accountdetect.detect_full";
    public static final String ACCOUNTDETECT_RESAMPLES = "stage.accountdetect.resamples";
    public static final String GMAILSYNC_DISCOVER = "stage.gmailsync.discover";
    public static final String GMAILSYNC_SWEEP = "stage.gmailsync.sweep";
    public static final String GMAILSYNC_SETTLE_PARKED = "stage.gmailsync.settle_parked";
    public static final String GMAILSYNC_PROCESS = "stage.gmailsync.process";
    public static final String GMAILSYNC_MESSAGE = "stage.gmailsync.message";
    public static final String GMAILSYNC_ARCHIVE_IGNORED = "stage.gmailsync.archive_ignored";
    public static final String GMAILSYNC_CATEGORISE = "stage.gmailsync.categorise";
    public static final String GMAILSYNC_FETCH = "stage.gmailsync.fetch";
    public static final String GMAILSYNC_CLASSIFY = "stage.gmailsync.classify";
    public static final String GMAILSYNC_DOCUMENT = "stage.gmailsync.document";
    public static final String GMAILSYNC_STATEMENT_RECONCILE = "stage.gmailsync.statement_reconcile";
    public static final String GMAILSYNC_ENGINE = "stage.gmailsync.engine";
    public static final String GMAILSYNC_EXTRACT = "stage.gmailsync.extract";
    public static final String GMAILSYNC_RESOLVE_ACCOUNT = "stage.gmailsync.resolve_account";
    public static final String GMAILSYNC_WRITE = "stage.gmailsync.write";
    public static final String STATEMENT_PASSWORD_MATCH = "stage.statement.password_match";
    public static final String STATEMENT_BANK_RULES = "stage.statement.bank_rules";
    public static final String STATEMENT_PARSE = "stage.statement.parse";
    public static final String STATEMENT_PERSIST = "stage.statement.persist";
    public static final String STATEMENT_OPEN = "stage.statement.open";
    public static final String STATEMENT_DETAILS_FILL = "stage.statement.details_fill";
    public static final String STATEMENT_IDENTIFY = "stage.statement.identify";
    public static final String STATEMENT_UNLOCK_SAVED = "stage.statement.unlock_saved";
    public static final String STATEMENT_INGEST_ACCOUNT = "stage.statement.ingest_account";
    public static final String STATEMENT_UNLOCK = "stage.statement.unlock";
    public static final String MATCH_STATEMENT_BATCH = "stage.match.statement_batch";
    public static final String MATCH_REMATCH_FOREIGN = "stage.match.rematch_foreign";
    public static final String MATCH_REMATCH_REASONS = "stage.match.rematch_reasons";
    public static final String MATCH_REMATCH_STATEMENTS = "stage.match.rematch_statements";
    public static final String MATCH_REMATCH_REQUEUE = "stage.match.rematch_requeue";
    public static final String REWARDS_LOAD_INPUTS = "stage.rewards.load_inputs";
    public static final String REWARDS_PREPARE = "stage.rewards.prepare";
    public static final String REWARDS_LOAD_TRANSACTIONS = "stage.rewards.load_transactions";
    public static final String REWARDS_LOAD_LINKS = "stage.rewards.load_links";
    public static final String REWARDS_RUN = "stage.rewards.run";
    public static final String REWARDS_BUILD_REPORT = "stage.rewards.build_report";
    public static final String PRICES_LOAD_TARGETS = "stage.prices.load_targets";
    public static final String PRICES_CLASSIFY = "stage.prices.classify";
    public static final String PRICES_FETCH = "stage.prices.fetch";
    public static final String PRICES_STORE = "stage.prices.store";
    public static final String CHAT_LLM = "stage.chat.llm";
    public static final String CHAT_SQL = "stage.chat.sql";
    public static final String CHAT_TOOL = "stage.chat.tool";
    public static final String CHAT_ORCHESTRATE = "stage.chat.orchestrate";
    public static final String UNCATEGORIZED_LOAD_ROWS = "stage.uncategorized.load_rows";
    public static final String UNCATEGORIZED_ANALYSE = "stage.uncategorized.analyse";
    public static final String SECTION_RUN = "stage.section.run";
    public static final String SEARCH_QUERY = "stage.search.query";

    /** Every fixed stage op, for the series-budget and no-bucket tests. */
    public static final List<String> STAGES = List.of(
            DETECTOR_CLUSTER_ROLES, DETECTOR_OCCURRENCES, DETECTOR_SLOT_CLASSES,
            DETECTOR_STATEMENT_TIE, DETECTOR_EVIDENCE, DETECTOR_ENTITIES,
            DETECTOR_VARIANTS, DETECTOR_LIFECYCLE, DETECTOR_LIMITS,
            DETECTOR_FINISH, ACCOUNTDETECT_LOAD_STORED, ACCOUNTDETECT_STATEMENTS_FP,
            ACCOUNTDETECT_LOAD_WRITTEN, ACCOUNTDETECT_DB_STATEMENTS, ACCOUNTDETECT_SINK,
            ACCOUNTDETECT_LIST, ACCOUNTDETECT_METADATA, ACCOUNTDETECT_CLUSTER_SELECT,
            ACCOUNTDETECT_FULL_BODIES, ACCOUNTDETECT_MAIL_PDFS, ACCOUNTDETECT_DERIVE,
            ACCOUNTDETECT_DETECT_FULL, ACCOUNTDETECT_RESAMPLES, GMAILSYNC_DISCOVER,
            GMAILSYNC_SWEEP, GMAILSYNC_SETTLE_PARKED, GMAILSYNC_PROCESS,
            GMAILSYNC_MESSAGE, GMAILSYNC_ARCHIVE_IGNORED, GMAILSYNC_CATEGORISE,
            GMAILSYNC_FETCH, GMAILSYNC_CLASSIFY, GMAILSYNC_DOCUMENT,
            GMAILSYNC_STATEMENT_RECONCILE, GMAILSYNC_ENGINE, GMAILSYNC_EXTRACT,
            GMAILSYNC_RESOLVE_ACCOUNT, GMAILSYNC_WRITE, STATEMENT_PASSWORD_MATCH,
            STATEMENT_BANK_RULES, STATEMENT_PARSE, STATEMENT_PERSIST,
            STATEMENT_OPEN, STATEMENT_DETAILS_FILL, STATEMENT_IDENTIFY,
            STATEMENT_UNLOCK_SAVED, STATEMENT_INGEST_ACCOUNT, STATEMENT_UNLOCK,
            MATCH_STATEMENT_BATCH, MATCH_REMATCH_FOREIGN, MATCH_REMATCH_REASONS,
            MATCH_REMATCH_STATEMENTS, MATCH_REMATCH_REQUEUE, REWARDS_LOAD_INPUTS,
            REWARDS_PREPARE, REWARDS_LOAD_TRANSACTIONS, REWARDS_LOAD_LINKS,
            REWARDS_RUN, REWARDS_BUILD_REPORT, PRICES_LOAD_TARGETS,
            PRICES_CLASSIFY, PRICES_FETCH, PRICES_STORE,
            CHAT_LLM, CHAT_SQL, CHAT_TOOL,
            CHAT_ORCHESTRATE, UNCATEGORIZED_LOAD_ROWS, UNCATEGORIZED_ANALYSE,
            SECTION_RUN, SEARCH_QUERY);

    /** Upper bound on AutoLinker implementations, used only by the series-budget test. */
    public static final int MAX_AUTOLINKERS = 12;


    /** Upper bound on configured LLM providers, used only by the series-budget test. */
    public static final int MAX_LLM_PROVIDERS = 12;

    public static String job(String jobTypeName) {
        return "job." + (jobTypeName == null ? "unknown" : jobTypeName.toLowerCase(Locale.ROOT));
    }

    public static String gmail(String call) {
        return "ext.gmail." + call;
    }

    public static String autolink(String linkerClassName) {
        return "stage.match.autolink." + Timing.segment(linkerClassName);
    }

    public static String llm(String providerId) {
        return "ext.llm." + Timing.segment(providerId);
    }
}
