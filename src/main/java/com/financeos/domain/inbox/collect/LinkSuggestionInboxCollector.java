package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.transaction.autolink.LinkSuggestionService;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stored link suggestions as one needs_look row per kind ("3 possible transfers"), never one per
 * suggestion. A summary row: it can be dismissed and returns when its count changes.
 */
@Component
public class LinkSuggestionInboxCollector implements InboxCollector {

    static final String HREF = "/transactions/review";

    private final LinkSuggestionService suggestions;

    public LinkSuggestionInboxCollector(LinkSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        Map<SuggestionKind, Integer> counts = suggestions.openCounts(userId);
        List<InboxItemResponse> rows = new ArrayList<>();
        for (SuggestionKind kind : SuggestionKind.values()) {
            int n = counts.getOrDefault(kind, 0);
            if (n <= 0) {
                continue;
            }
            rows.add(InboxRows.summary(InboxKinds.linkSuggestionKey(kind.name()), InboxKinds.LINK_SUGGESTION, SEVERITY_WARNING,
                    SECTION_NEEDS_LOOK, title(kind, n), "Confirm or dismiss", HREF, n,
                    List.of(InboxActionResponse.navigate("review", "Review", HREF), InboxRows.dismiss())));
        }
        return rows;
    }

    static String title(SuggestionKind kind, int n) {
        return switch (kind) {
            case TRANSFER -> n + (n == 1 ? " possible transfer" : " possible transfers");
            case CC_PAYMENT -> n + (n == 1 ? " possible card payment" : " possible card payments");
            case REFUND -> n + (n == 1 ? " possible refund" : " possible refunds");
            case EMI, LENDING, DIVIDEND -> throw new IllegalArgumentException("No stored suggestions of kind " + kind);
        };
    }
}
