package com.financeos.domain.inbox.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestion;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionRepository;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionKind;
import com.financeos.domain.instrument.corporateaction.detect.SuggestionReason;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorporateActionInboxCollectorsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private final CorporateActionSuggestionRepository repo = mock(CorporateActionSuggestionRepository.class);
    private final UUID userId = UUID.randomUUID();

    private static CorporateActionSuggestion suggestion(SuggestionKind kind, int from, int to, SuggestionReason reason) {
        Instrument acme = new Instrument();
        acme.setId(UUID.randomUUID());
        acme.setName("Acme Ltd");
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(acme);
        s.setKind(kind);
        s.setRatioFrom(from);
        s.setRatioTo(to);
        s.setReason(reason);
        s.setExDate(LocalDate.of(2026, 10, 28));
        return s;
    }

    @Test
    void reviewRowsCarryKindLabelReasonTextAndNoDismiss() {
        CorporateActionSuggestion demerger = suggestion(SuggestionKind.demerger, 1, 1, SuggestionReason.NEEDS_DETAILS);
        CorporateActionSuggestion other = suggestion(SuggestionKind.other, 1, 1, SuggestionReason.COMBINED_SAME_DAY);
        CorporateActionSuggestion split = suggestion(SuggestionKind.split, 1, 5, SuggestionReason.YAHOO_MISSING);
        when(repo.findPendingOfKinds(eq(userId), any())).thenReturn(List.of(demerger, other, split));

        List<InboxItemResponse> rows = new CorporateActionReviewInboxCollector(repo).collect(userId, TODAY);

        assertThat(rows).extracting(InboxItemResponse::title)
                .containsExactly("Demerger on Acme Ltd", "Corporate action on Acme Ltd", "Split on Acme Ltd");
        assertThat(rows).extracting(InboxItemResponse::subtitle).containsExactly(
                "Ex-date 28 Oct · needs ratio and cost split",
                "Ex-date 28 Oct · more than one action on this date",
                "Ex-date 28 Oct · could not be confirmed");
        InboxItemResponse r = rows.get(0);
        assertThat(r.kind()).isEqualTo(InboxKinds.CORPORATE_ACTION_REVIEW);
        assertThat(r.key()).isEqualTo("ca_review:" + demerger.getId());
        assertThat(r.section()).isEqualTo(InboxItemResponse.SECTION_NEEDS_LOOK);
        assertThat(r.actions()).extracting(a -> a.type()).doesNotContain("dismiss");
        assertThat(r.refs().suggestionId()).isEqualTo(demerger.getId());
        assertThat(r.refs().instrumentId()).isEqualTo(demerger.getInstrument().getId());
    }

    @Test
    void reviewReasonTextForRemainingReasons() {
        when(repo.findPendingOfKinds(eq(userId), any())).thenReturn(List.of(
                suggestion(SuggestionKind.merger, 1, 1, SuggestionReason.UNCLEAR_TEXT),
                suggestion(SuggestionKind.split, 1, 2, SuggestionReason.NO_PRICE_SYMBOL),
                suggestion(SuggestionKind.split, 1, 2, SuggestionReason.YAHOO_MISMATCH)));
        assertThat(new CorporateActionReviewInboxCollector(repo).collect(userId, TODAY)).extracting(InboxItemResponse::subtitle)
                .containsExactly("Ex-date 28 Oct · needs a check", "Ex-date 28 Oct · could not be confirmed",
                        "Ex-date 28 Oct · could not be confirmed");
    }

    @Test
    void possibleDuplicateReasonText() {
        when(repo.findPendingOfKinds(eq(userId), any())).thenReturn(List.of(
                suggestion(SuggestionKind.bonus, 1, 2, SuggestionReason.POSSIBLE_DUPLICATE)));
        assertThat(new CorporateActionReviewInboxCollector(repo).collect(userId, TODAY)).extracting(InboxItemResponse::subtitle)
                .containsExactly("Ex-date 28 Oct · may already be recorded");
    }

    @Test
    void pendingQueryHidesRowsWithASameDayActionInOneQuery() throws Exception {
        var m = CorporateActionSuggestionRepository.class.getMethod("findPendingOfKinds", UUID.class, java.util.Collection.class);
        String q = m.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        assertThat(q).contains("NOT EXISTS").contains("ca.exDate = s.exDate").contains("ca.instrument.id = s.instrument.id");
    }
}
