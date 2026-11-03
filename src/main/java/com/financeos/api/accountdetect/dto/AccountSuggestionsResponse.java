package com.financeos.api.accountdetect.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The account-detect scan's progress plus the NEW suggestions (children nested under their parent suggestion). */
public record AccountSuggestionsResponse(Scan scan, List<Item> items) {

    /** status: IDLE, RUNNING, PAUSED_AUTH, FAILED, or NOT_STARTED before the first run. */
    @Schema(name = "AccountSuggestionScan")
    public record Scan(String status, boolean firstScanDone, int mailsSeen, int mailsTotal, boolean gmailConnected) {}

    @Schema(name = "AccountSuggestionBank")
    public record Bank(String id, String name) {}

    @Schema(name = "AccountSuggestionParentAccount")
    public record ParentAccount(UUID id, String name, String last4) {}

    @Schema(name = "AccountSuggestionCandidateAccount")
    public record CandidateAccount(UUID id, String name) {}

    @Schema(name = "AccountSuggestionItem")
    public record Item(UUID id, String kind, String kindConf, String band, Bank bank, String last4,
                       String variant, String variantConf, List<String> variantCandidates,
                       String limitBucket, String limitConf, BigDecimal limitAmount, boolean passwordFromProfile, boolean notInYourName, String lifecycle, String lifecycleConf,
                       LocalDate firstSeenOn, LocalDate lastSeenOn, int evidenceMails,
                       ParentAccount parentAccount, UUID parentSuggestionId, CandidateAccount candidateAccount,
                       List<Child> children, String caption) {}

    /** A card that comes with its parent suggestion (an add-on or debit card); one level only. */
    @Schema(name = "AccountSuggestionChild")
    public record Child(UUID id, String kind, String band, String last4, String lifecycle, String lifecycleConf,
                        LocalDate firstSeenOn, LocalDate lastSeenOn, int evidenceMails) {}
}
