package com.financeos.gmail.domain;

import java.util.regex.Pattern;

/** Why a Gmail ledger row needs attention; mirrors the client's describeAttentionItem labels. */
public enum AttentionReason {
    NO_ACCOUNT,
    IMPORT_OFF,
    NO_AI_KEY,
    LOCKED_STATEMENT,
    UNREADABLE_STATEMENT,
    FAILED;

    private static final Pattern NO_KEYS = Pattern.compile("no (active )?llm api keys|add an api key", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECRYPT_FAILED = Pattern.compile("could not be decrypted", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATEMENT_PARSE = Pattern.compile("^statement parse failed", Pattern.CASE_INSENSITIVE);

    public static AttentionReason of(GmailProcessedStatus status, String error) {
        if (status == GmailProcessedStatus.UNRESOLVED_ACCOUNT) return NO_ACCOUNT;
        if (status == GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN) return IMPORT_OFF;
        String e = error == null ? "" : error;
        if (NO_KEYS.matcher(e).find()) return NO_AI_KEY;
        if (DECRYPT_FAILED.matcher(e).find()) return LOCKED_STATEMENT;
        if (STATEMENT_PARSE.matcher(e).find()) return UNREADABLE_STATEMENT;
        return FAILED;
    }
}
