package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionRepository;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code holding_oversold}: one row per pending {@code oversold} suggestion (sells beyond the lots held). Generic
 * dismiss is allowed.
 */
@Component
public class HoldingOversoldInboxCollector implements InboxCollector {

    static final String HREF = "/investments/corporate-actions";

    private final CorporateActionSuggestionRepository suggestions;

    public HoldingOversoldInboxCollector(CorporateActionSuggestionRepository suggestions) {
        this.suggestions = suggestions;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        return suggestions.findPendingOfKinds(userId, Set.of(SuggestionKind.oversold)).stream()
                .map(HoldingOversoldInboxCollector::row).toList();
    }

    private static InboxItemResponse row(CorporateActionSuggestion s) {
        return InboxRows.item(InboxKinds.holdingOversoldKey(s.getId()), InboxKinds.HOLDING_OVERSOLD,
                SEVERITY_WARNING, SECTION_NEEDS_LOOK, "Sold more " + s.getInstrument().getName() + " than you held",
                "A split, bonus or earlier buy may be missing", HREF, null, s.getExDate(),
                List.of(InboxRows.open(HREF), InboxRows.dismiss()),
                InboxRefsResponse.ofCorporateActionSuggestion(s.getId(), s.getInstrument().getId()));
    }
}
