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
    public static final String GMAIL_RECONNECT = "gmail_reconnect";
    public static final String GMAIL_ATTENTION = "gmail_attention";
    public static final String REVIEW = "review";
    public static final String JOB = "job";
    public static final String REWARD_MILESTONE = "reward_milestone";
    public static final String REWARD_CAP = "reward_cap";

    /** Every kind the inbox can produce, in display order. */
    public static final List<String> ALL = List.of(BILL, EMI, LENDING, STATEMENT_EXPECTED, GMAIL_RECONNECT,
            GMAIL_ATTENTION, REVIEW, JOB, REWARD_MILESTONE, REWARD_CAP);

    /** The two summary rows have fixed keys; everything else is keyed by its subject. */
    public static final String KEY_GMAIL_ATTENTION = "gmail-attention";
    public static final String KEY_REVIEW = "review";

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

    public static String gmailReconnectKey(UUID connectionId) {
        return "gmail-reconnect:" + connectionId;
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

    /** The push deep link for a row: the inbox, focused on that key. */
    public static String inboxHref(String key) {
        return "/inbox?item=" + key;
    }

    /** Summary rows are counts behind one link: they can be dismissed but not snoozed. */
    public static boolean isSummaryKey(String key) {
        return KEY_GMAIL_ATTENTION.equals(key) || KEY_REVIEW.equals(key);
    }

    // ---------------------------------------------------------------- labels and landing pages

    public static String label(String kind) {
        return switch (kind) {
            case BILL -> "Card bills";
            case EMI -> "EMIs";
            case LENDING -> "Lending returns";
            case STATEMENT_EXPECTED -> "Missing statements";
            case GMAIL_RECONNECT -> "Gmail reconnect";
            case GMAIL_ATTENTION -> "Gmail attention";
            case REVIEW -> "Transactions to review";
            case JOB -> "Finished jobs";
            case REWARD_MILESTONE -> "Reward milestones";
            case REWARD_CAP -> "Reward caps";
            default -> kind;
        };
    }

    /** Where a whole kind lands when more than one row sits behind it. */
    public static String landingHref(String kind) {
        return switch (kind) {
            case BILL -> "/upcoming";
            case EMI -> "/loans";
            case LENDING -> "/loans/lendings";
            case STATEMENT_EXPECTED -> "/transactions/import";
            case GMAIL_RECONNECT -> "/settings/gmail";
            case GMAIL_ATTENTION -> "/settings/gmail?focus=attention";
            case REVIEW -> "/transactions/review";
            case JOB -> "/settings/activity";
            case REWARD_MILESTONE, REWARD_CAP -> "/rewards";
            default -> "/inbox";
        };
    }
}
