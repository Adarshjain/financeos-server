package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_INFO;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_INFO;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionRepository;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code corporate_action_applied}: a split or bonus the detector applied on its own in the last
 * {@value #RECENT_DAYS} days, shown as information (one row per suggestion) so the user can see it and undo it.
 */
@Component
public class CorporateActionAppliedInboxCollector implements InboxCollector {

    static final int RECENT_DAYS = 30;
    static final String HREF = "/investments/corporate-actions";

    private final CorporateActionSuggestionRepository suggestions;

    public CorporateActionAppliedInboxCollector(CorporateActionSuggestionRepository suggestions) {
        this.suggestions = suggestions;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        java.time.Instant since = today.minusDays(RECENT_DAYS).atStartOfDay(AppTime.zone()).toInstant();
        return suggestions.findRecentAutoApplied(userId, since).stream().map(this::row).toList();
    }

    private InboxItemResponse row(CorporateActionSuggestion s) {
        String name = s.getInstrument().getName();
        String title = s.getKind() == SuggestionKind.bonus ? "Bonus " + ratio(s, true) + " applied to " + name
                : "Split " + ratio(s, false) + " applied to " + name;
        return InboxRows.item(InboxKinds.corporateActionAppliedKey(s.getId()), InboxKinds.CORPORATE_ACTION_APPLIED,
                SEVERITY_INFO, SECTION_INFO, title,
                "Ex-date " + InboxRows.date(s.getExDate()) + " · detected automatically", HREF, null, s.getExDate(),
                List.of(InboxRows.open(HREF), InboxRows.dismiss()),
                InboxRefsResponse.ofCorporateActionSuggestion(s.getId(), s.getInstrument().getId()));
    }

    /** A split reads {@code from:to}; a bonus reads {@code (to-from):from} (1 to 2 units is a 1:1 bonus). */
    private static String ratio(CorporateActionSuggestion s, boolean bonus) {
        int from = s.getRatioFrom() == null ? 0 : s.getRatioFrom();
        int to = s.getRatioTo() == null ? 0 : s.getRatioTo();
        return bonus ? (to - from) + ":" + from : from + ":" + to;
    }
}
