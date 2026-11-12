package com.financeos.domain.inbox;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The inbox vocabulary: one {@code kind} per producer, the stable item keys, and the human label
 * plus landing page per kind (what the {@code attention} datasource and the dashboard widget show).
 */
public final class InboxKinds {

    public static final String BILL = "bill";
    public static final String EMI = "emi";
    public static final String LENDING = "lending";
    public static final String STATEMENT_EXPECTED = "statement_expected";
    public static final String STATEMENT_DETAILS_MISSING = "statement_details_missing";
    public static final String GMAIL_RECONNECT = "gmail_reconnect";
    public static final String GMAIL_ATTENTION = "gmail_attention";
    public static final String REVIEW = "review";
    public static final String JOB = "job";
    public static final String REWARD_MILESTONE = "reward_milestone";
    public static final String REWARD_CAP = "reward_cap";
    public static final String EPF_MATCH = "epf_match";
    /** No longer produced (link suggestions show on the transaction); kept so persisted dismissals still resolve a label. */
    public static final String LINK_SUGGESTION = "link_suggestion";
    public static final String UPLOAD_NEEDS_ACCOUNT = "upload_needs_account";
    public static final String NPS_PASSWORD = "nps_password";
    public static final String NPS_NEW_ACCOUNT = "nps_new_account";
    public static final String NPS_MATCH = "nps_match";
    public static final String NPS_RECORD = "nps_record";
    public static final String NPS_SETUP = "nps_setup";
    public static final String CORPORATE_ACTION_APPLIED = "corporate_action_applied";
    public static final String CORPORATE_ACTION_REVIEW = "corporate_action_review";
    public static final String STOCK_NOT_TRADING = "stock_not_trading";
    public static final String HOLDING_OVERSOLD = "holding_oversold";

    /** Every kind the inbox can produce, in display order. */
    public static final List<String> ALL = List.of(BILL, EMI, LENDING, STATEMENT_EXPECTED, STATEMENT_DETAILS_MISSING,
            GMAIL_RECONNECT, GMAIL_ATTENTION, REVIEW, JOB, REWARD_MILESTONE, REWARD_CAP, EPF_MATCH, UPLOAD_NEEDS_ACCOUNT,
            NPS_PASSWORD, NPS_NEW_ACCOUNT, NPS_MATCH, NPS_RECORD, NPS_SETUP,
            CORPORATE_ACTION_APPLIED, CORPORATE_ACTION_REVIEW, STOCK_NOT_TRADING, HOLDING_OVERSOLD);

    /** The summary rows have fixed keys; everything else is keyed by its subject. */
    public static final String KEY_GMAIL_ATTENTION = "gmail-attention";
    public static final String KEY_REVIEW = "review";
    public static final String KEY_JOBS_FAILED = "jobs-failed";

    /**
     * Bills and EMIs show in the inbox once they fall due within this many days (or are overdue);
     * pushes link to the inbox row only inside that window, so the tap always lands on a listed item.
     */
    public static final int DUE_SOON_DAYS = 7;

    private InboxKinds() {
    }

    // ---------------------------------------------------------------- keys

    public static String billKey(UUID statementId) {
        return "bill:" + statementId;
    }

    public static String billAwaitingKey(UUID accountId) {
        return "bill-awaiting:" + accountId;
    }

    public static String emiKey(UUID loanId, int installmentSeq) {
        return "emi:" + loanId + ":" + installmentSeq;
    }

    public static String lendingKey(UUID counterpartyId) {
        return "lending:" + counterpartyId;
    }

    public static String statementExpectedKey(UUID accountId, LocalDate periodEnd) {
        return "statement-expected:" + accountId + ":" + periodEnd;
    }

    /** One per card, so it stays the same row while statements are fixed one by one. */
    public static String statementDetailsMissingKey(UUID accountId) {
        return "statement-details-missing:" + accountId;
    }

    public static String gmailReconnectKey(UUID connectionId) {
        return "gmail-reconnect:" + connectionId;
    }

    /** One per held file, so resolving one never hides another. */
    public static String uploadNeedsAccountKey(UUID heldUploadId) {
        return "upload-needs-account:" + heldUploadId;
    }

    public static String jobKey(UUID jobId) {
        return "job:" + jobId;
    }

    /** Per window, so a milestone achieved again next window is a new row (not still dismissed). */
    public static String rewardMilestoneKey(UUID milestoneId, LocalDate windowStart) {
        return windowStart == null ? "reward-milestone:" + milestoneId : "reward-milestone:" + milestoneId + ":" + windowStart;
    }

    /** Per window, so a cap exhausted again next window is a new row (not still dismissed). */
    public static String rewardCapKey(UUID ownerId, LocalDate windowStart) {
        return windowStart == null ? "reward-cap:" + ownerId : "reward-cap:" + ownerId + ":" + windowStart;
    }

    /** One per withdrawal and candidate credit, so a dismissed pairing never hides a different one. */
    public static String epfMatchKey(UUID entryId, UUID transactionId) {
        return "epf_match:" + entryId + ":" + transactionId;
    }

    /** One per detected NPS PRAN (the detection key), so answering one never hides another. */
    public static String npsPendingKey(String kind, UUID detectionKey) {
        return kind + ":" + detectionKey;
    }

    /** One grouped row for the locked NPS statements; the count is in the key so more locked mails bring it back. */
    public static String npsPasswordKey(int count) {
        return NPS_PASSWORD + ":" + count;
    }

    /** The one "Set up NPS?" row of a user. */
    public static final String KEY_NPS_SETUP = "nps_setup";

    /** One per contribution and candidate payment, so a dismissed pairing never hides a different one. */
    public static String npsMatchKey(UUID entryId, UUID transactionId) {
        return "nps_match:" + entryId + ":" + transactionId;
    }

    /** One per payment that may be an NPS contribution. */
    public static String npsRecordKey(UUID transactionId) {
        return "nps_record:" + transactionId;
    }

    public static String corporateActionAppliedKey(UUID suggestionId) {
        return "ca_applied:" + suggestionId;
    }

    public static String corporateActionReviewKey(UUID suggestionId) {
        return "ca_review:" + suggestionId;
    }

    public static String stockNotTradingKey(UUID instrumentId) {
        return "stock_not_trading:" + instrumentId;
    }

    public static String holdingOversoldKey(UUID suggestionId) {
        return "holding_oversold:" + suggestionId;
    }

    /** One grouped row per suggestion kind (TRANSFER, CC_PAYMENT, REFUND), so the count changes but the key does not. */
    public static final String KEY_LINK_SUGGESTION_PREFIX = "link-suggestion:";

    public static String linkSuggestionKey(String suggestionKind) {
        return KEY_LINK_SUGGESTION_PREFIX + suggestionKind;
    }

    // ---------------------------------------------------------------- destination hrefs (shared by collectors and pushes)

    public static String billHref(UUID accountId) {
        return "/accounts/" + accountId;
    }

    public static String emiHref(UUID loanId, int installmentSeq) {
        return "/loans/" + loanId + "?installment=" + installmentSeq;
    }

    public static String lendingHref(UUID counterpartyId, boolean lent) {
        return "/lending/" + counterpartyId + (lent ? "?export=1" : "");
    }

    public static String statementExpectedHref(UUID accountId) {
        return "/accounts/" + accountId + "/statements";
    }

    public static final String HREF_GMAIL_RECONNECT = "/data-sources/connections";
    public static final String HREF_GMAIL_ATTENTION = "/data-sources/connections?focus=attention";

    /** Summary rows are counts behind one link: they can be dismissed but not snoozed. */
    public static boolean isSummaryKey(String key) {
        return KEY_GMAIL_ATTENTION.equals(key) || KEY_REVIEW.equals(key) || KEY_JOBS_FAILED.equals(key) || (key != null && key.startsWith(KEY_LINK_SUGGESTION_PREFIX));
    }

    // ---------------------------------------------------------------- labels and landing pages

    public static String label(String kind) {
        return switch (kind) {
            case BILL -> "Card bills";
            case EMI -> "EMIs";
            case LENDING -> "Lending returns";
            case STATEMENT_EXPECTED -> "Missing statements";
            case STATEMENT_DETAILS_MISSING -> "Statement details missing";
            case GMAIL_RECONNECT -> "Gmail reconnect";
            case GMAIL_ATTENTION -> "Gmail attention";
            case REVIEW -> "Transactions to review";
            case JOB -> "Finished jobs";
            case REWARD_MILESTONE -> "Reward milestones";
            case REWARD_CAP -> "Reward caps";
            case EPF_MATCH -> "EPF withdrawals to match";
            case LINK_SUGGESTION -> "Possible links";
            case UPLOAD_NEEDS_ACCOUNT -> "Statements needing an account";
            case NPS_PASSWORD -> "NPS statements needing a password";
            case NPS_NEW_ACCOUNT -> "New NPS accounts";
            case NPS_MATCH -> "NPS contributions to match";
            case NPS_RECORD -> "Possible NPS payments";
            case NPS_SETUP -> "Set up NPS";
            case CORPORATE_ACTION_APPLIED -> "Corporate actions applied";
            case CORPORATE_ACTION_REVIEW -> "Corporate actions to review";
            case STOCK_NOT_TRADING -> "Stocks no longer trading";
            case HOLDING_OVERSOLD -> "Sold more than held";
            default -> kind;
        };
    }

    /** Where a whole kind lands when more than one row sits behind it. */
    public static String landingHref(String kind) {
        return switch (kind) {
            case BILL -> "/upcoming";
            case EMI -> "/accounts";
            case LENDING -> "/lending";
            case STATEMENT_EXPECTED -> "/transactions/import";
            case STATEMENT_DETAILS_MISSING -> "/accounts";
            case GMAIL_RECONNECT -> "/data-sources/connections";
            case GMAIL_ATTENTION -> "/data-sources/connections?focus=attention";
            case REVIEW -> "/transactions/review";
            case JOB -> "/data-sources/sync";
            case REWARD_MILESTONE, REWARD_CAP -> "/rewards";
            case EPF_MATCH -> "/investments/epf";
            case LINK_SUGGESTION -> "/transactions/review";
            case UPLOAD_NEEDS_ACCOUNT -> "/transactions/import";
            case NPS_PASSWORD, NPS_NEW_ACCOUNT, NPS_MATCH, NPS_RECORD, NPS_SETUP -> "/investments/nps";
            case CORPORATE_ACTION_APPLIED, CORPORATE_ACTION_REVIEW, STOCK_NOT_TRADING, HOLDING_OVERSOLD -> "/investments/corporate-actions";
            default -> "/inbox";
        };
    }

    /** The path of an href: everything before the query string. */
    public static String pathOf(String href) {
        if (href == null) {
            return "";
        }
        int q = href.indexOf('?');
        return q < 0 ? href : href.substring(0, q);
    }

    /** What a page is called in a grouped inbox row ("<label>: N to review"). */
    public static String pageLabel(String path) {
        return switch (path) {
            case "/investments/corporate-actions" -> "Corporate actions";
            case "/investments/nps" -> "NPS";
            case "/investments/epf" -> "EPF";
            case "/transactions/review" -> "Transactions";
            case "/transactions/import" -> "Statement uploads";
            case "/data-sources/connections" -> "Gmail";
            case "/data-sources/sync" -> "Sync";
            case "/upcoming" -> "Upcoming";
            case "/rewards" -> "Rewards";
            default -> {
                if (path.startsWith("/accounts/") && path.endsWith("/statements")) {
                    yield "Card statements";
                } else if (path.startsWith("/accounts/")) {
                    yield "Card";
                } else if (path.startsWith("/loans/")) {
                    yield "Loan";
                } else if (path.startsWith("/lending/")) {
                    yield "Lending";
                }
                yield "Needs attention";
            }
        };
    }
}
