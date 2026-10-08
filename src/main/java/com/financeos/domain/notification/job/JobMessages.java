package com.financeos.domain.notification.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.push.PushMessage;

/**
 * Push texts for finished jobs. The body is read leniently from the stored result JSON (each
 * handler's own DTO), so an unknown shape still produces a sensible line. Jobs are "quiet when
 * visible": an open FinanceOS tab already toasts the outcome.
 */
public final class JobMessages {

    private static final int MAX_ERROR_CHARS = 140;

    private JobMessages() {
    }

    public static String label(JobType type) {
        return switch (type) {
            case STATEMENT_INGEST -> "Statement import";
            case INVESTMENT_IMPORT_COMMIT -> "Investment import";
            case BROKER_RECONCILE_COMMIT -> "Broker reconciliation";
            case RULE_APPLY -> "Rule apply";
            case GMAIL_SYNC -> "Gmail sync";
            case PRICE_REFRESH -> "Price refresh";
        };
    }

    /** Where a tap lands: the page that started the job, which already shows its inline job panel. */
    public static String url(JobType type) {
        return switch (type) {
            case STATEMENT_INGEST -> "/settings/ingest";
            case INVESTMENT_IMPORT_COMMIT, BROKER_RECONCILE_COMMIT -> "/investments";
            default -> "/settings/jobs?type=" + type.name();
        };
    }

    public static PushMessage forJob(Job job, ObjectMapper mapper) {
        String label = label(job.getType());
        if (job.getStatus() == JobStatus.FAILED) {
            String reason = job.getErrorMessage() == null || job.getErrorMessage().isBlank()
                    ? "Open the job to see what went wrong."
                    : truncate(job.getErrorMessage().strip());
            return new PushMessage(label + " failed", reason, url(job.getType()), "job-" + job.getId(), true);
        }
        return new PushMessage(label + " finished", summary(job, mapper), url(job.getType()), "job-" + job.getId(), true);
    }

    static String summary(Job job, ObjectMapper mapper) {
        JsonNode result = null;
        if (job.getResult() != null && !job.getResult().isBlank()) {
            try {
                result = mapper.readTree(job.getResult());
            } catch (Exception ignored) {
                // an unreadable result still gets a generic line
            }
        }
        if (result == null || !result.isObject()) {
            return "Tap to see the result.";
        }
        switch (job.getType()) {
            case STATEMENT_INGEST -> {
                int created = result.path("totalCreated").asInt(0);
                int files = result.path("filesProcessed").asInt(0);
                int dupes = result.path("totalDuplicatesFound").asInt(0);
                String text = plural(created, "transaction") + " added from " + plural(files, "file");
                return dupes > 0 ? text + " · " + dupes + " flagged as possible duplicates" : text;
            }
            case INVESTMENT_IMPORT_COMMIT, BROKER_RECONCILE_COMMIT -> {
                int committed = result.path("committed").asInt(0);
                int skipped = result.path("skipped").asInt(0);
                int failed = result.path("failed").isArray() ? result.path("failed").size() : 0;
                StringBuilder text = new StringBuilder(plural(committed, "row")).append(" imported");
                if (skipped > 0) {
                    text.append(" · ").append(skipped).append(" skipped");
                }
                if (failed > 0) {
                    text.append(" · ").append(failed).append(" failed");
                }
                return text.toString();
            }
            case RULE_APPLY -> {
                return plural(result.path("appliedCount").asInt(0), "transaction") + " categorised";
            }
            default -> {
                return "Tap to see the result.";
            }
        }
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, MAX_ERROR_CHARS - 1).stripTrailing() + "…";
    }
}
