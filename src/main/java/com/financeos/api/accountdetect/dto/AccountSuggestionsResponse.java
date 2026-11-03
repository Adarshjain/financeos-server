package com.financeos.api.accountdetect.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The account-detect scan's progress plus the NEW suggestions (children nested under their parent suggestion). */
public record AccountSuggestionsResponse(Scan scan, List<Item> items) {

    /** status: IDLE, RUNNING, PAUSED_AUTH, FAILED, or NOT_STARTED before the first run. */
    public record Scan(String status, boolean firstScanDone, int mailsSeen, int mailsTotal, boolean gmailConnected) {}

    public record Bank(String id, String name) {}

    public record ParentAccount(UUID id, String name, String last4) {}

    public record CandidateAccount(UUID id, String name) {}

    public record Item(UUID id, String kind, String kindConf, String band, Bank bank, String last4,
                       String variant, String variantConf, List<String> variantCandidates,
                       String limitBucket, String limitConf, String lifecycle, String lifecycleConf,
                       LocalDate firstSeenOn, LocalDate lastSeenOn, int evidenceMails,
                       ParentAccount parentAccount, UUID parentSuggestionId, CandidateAccount candidateAccount,
                       List<Item> children, String caption) {}
}
