package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionRepository;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionReason;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_action_review}: a corporate action the detector found but could not apply on its own, one row
 * per pending suggestion. No generic dismiss: the client answers through the suggestion endpoints.
 */
@Component
public class CorporateActionReviewInboxCollector implements InboxCollector {

    static final String HREF = "/investments/corporate-actions";
    static final Set<SuggestionKind> KINDS = Set.of(SuggestionKind.split, SuggestionKind.bonus,
            SuggestionKind.demerger, SuggestionKind.merger, SuggestionKind.other);

    private final CorporateActionSuggestionRepository suggestions;

    public CorporateActionReviewInboxCollector(CorporateActionSuggestionRepository suggestions) {
        this.suggestions = suggestions;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        return suggestions.findPendingOfKinds(userId, KINDS).stream().map(CorporateActionReviewInboxCollector::row).toList();
    }

    private static InboxItemResponse row(CorporateActionSuggestion s) {
        return InboxRows.item(InboxKinds.corporateActionReviewKey(s.getId()), InboxKinds.CORPORATE_ACTION_REVIEW,
                SEVERITY_WARNING, SECTION_NEEDS_LOOK, kindLabel(s.getKind()) + " on " + s.getInstrument().getName(),
                "Ex-date " + InboxRows.date(s.getExDate()) + " · " + reasonText(s.getReason()), HREF, null,
                s.getExDate(), List.of(InboxRows.open(HREF)),
                InboxRefsResponse.ofCorporateActionSuggestion(s.getId(), s.getInstrument().getId()));
    }

    private static String kindLabel(SuggestionKind kind) {
        return switch (kind) {
            case split -> "Split";
            case bonus -> "Bonus";
            case demerger -> "Demerger";
            case merger -> "Merger";
            default -> "Corporate action";
        };
    }

    private static String reasonText(SuggestionReason reason) {
        if (reason == null) {
            return "needs a check";
        }
        return switch (reason) {
            case YAHOO_MISSING, YAHOO_MISMATCH, NO_PRICE_SYMBOL -> "could not be confirmed";
            case COMBINED_SAME_DAY -> "more than one action on this date";
            case NEEDS_DETAILS -> "needs ratio and cost split";
            case UNCLEAR_TEXT -> "needs a check";
            case POSSIBLE_DUPLICATE -> "may already be recorded";
        };
    }
}
