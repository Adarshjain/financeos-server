package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CorporateActionChildCandidate;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.corporateaction.detect.DemergerChildFinder.ChildCandidate;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemergerCandidatesTest {

    private final DemergerChildFinder finder = mock(DemergerChildFinder.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final DemergerCandidates candidates = new DemergerCandidates(finder, instruments);
    private final LocalDate ex = LocalDate.of(2025, 1, 6);

    private CorporateActionSuggestion suggestion(SuggestionKind kind, SuggestionStatus status) {
        Instrument itc = new Instrument();
        itc.setId(UUID.randomUUID());
        itc.setName("ITC Limited");
        itc.setSymbol("ITC");
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(itc);
        s.setKind(kind);
        s.setStatus(status);
        s.setExDate(ex);
        return s;
    }

    @Test
    void pendingDemergerGetsCandidatesWithTheCatalogInstrument() {
        CorporateActionSuggestion s = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        when(finder.find("ITC Limited", "ITC", ex)).thenReturn(List.of(
                new ChildCandidate("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), "INE379A01028")));
        Instrument existing = new Instrument();
        existing.setId(UUID.randomUUID());
        existing.setIsin("INE379A01028");
        existing.setSymbol("ITCHOTELS");
        existing.setExchange("NSE");
        when(instruments.findByIsinIn(any())).thenReturn(List.of(existing));
        when(instruments.findByNseSymbolIn(any())).thenReturn(List.of(existing));

        List<CorporateActionChildCandidate> out = candidates.forSuggestion(s);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).yahooSymbol()).isEqualTo("ITCHOTELS.NS");
        assertThat(out.get(0).instrumentId()).isEqualTo(existing.getId());
    }

    @Test
    void candidateWithoutACatalogRowHasNoInstrumentId() {
        CorporateActionSuggestion s = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        when(finder.find("ITC Limited", "ITC", ex)).thenReturn(List.of(
                new ChildCandidate("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), null)));
        when(instruments.findByNseSymbolIn(any())).thenReturn(List.of());
        assertThat(candidates.forSuggestion(s).get(0).instrumentId()).isNull();
        verify(instruments, never()).findByIsinIn(any());
    }

    @Test
    void mergerAndAppliedDemergerGetNone() {
        CorporateActionSuggestion merger = suggestion(SuggestionKind.merger, SuggestionStatus.pending);
        CorporateActionSuggestion applied = suggestion(SuggestionKind.demerger, SuggestionStatus.auto_applied);
        Map<UUID, List<CorporateActionChildCandidate>> out = candidates.forSuggestions(List.of(merger, applied));
        assertThat(out).isEmpty();
        assertThat(candidates.forSuggestion(merger)).isEmpty();
        verify(finder, never()).find(any(), any(), any());
    }

    @Test
    void manyDemergersShareOneInstrumentLookup() {
        CorporateActionSuggestion a = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        CorporateActionSuggestion b = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        when(finder.find(any(), any(), any())).thenReturn(List.of(
                new ChildCandidate("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), "INE379A01028")));
        when(instruments.findByIsinIn(any())).thenReturn(List.of());
        when(instruments.findByNseSymbolIn(any())).thenReturn(List.of());
        assertThat(candidates.forSuggestions(List.of(a, b))).hasSize(2);
        verify(instruments, times(1)).findByNseSymbolIn(any());
    }

    private static Instrument row(String isin, String symbol, String exchange) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setIsin(isin);
        i.setSymbol(symbol);
        i.setExchange(exchange);
        return i;
    }

    @Test
    void isinMatchPrefersTheNseRowAndWinsOverSymbol() {
        CorporateActionSuggestion s = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        when(finder.find("ITC Limited", "ITC", ex)).thenReturn(List.of(
                new ChildCandidate("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), "INE379A01028")));
        Instrument bse = row("INE379A01028", "511111", "BSE");
        Instrument nse = row("INE379A01028", "ITCHOTELS", "NSE");
        Instrument bySymbolOnly = row("INE000000000", "ITCHOTELS", "NSE");
        when(instruments.findByIsinIn(any())).thenReturn(List.of(bse, nse));
        when(instruments.findByNseSymbolIn(any())).thenReturn(List.of(bySymbolOnly));
        assertThat(candidates.forSuggestion(s).get(0).instrumentId()).isEqualTo(nse.getId());
        when(instruments.findByIsinIn(any())).thenReturn(List.of(bse));
        assertThat(candidates.forSuggestion(s).get(0).instrumentId()).isEqualTo(bse.getId());
    }

    @Test
    void reviewServiceFillsPossibleChildrenOnlyForPendingDemerger() {
        var repo = mock(CorporateActionSuggestionRepository.class);
        var inv = mock(com.financeos.domain.investment.InvestmentService.class);
        var rule = mock(StockNotTradingRule.class);
        var prices = mock(com.financeos.domain.instrument.InstrumentPriceRepository.class);
        CorporateActionSuggestion pending = suggestion(SuggestionKind.demerger, SuggestionStatus.pending);
        CorporateActionSuggestion merger = suggestion(SuggestionKind.merger, SuggestionStatus.pending);
        CorporateActionSuggestion applied = suggestion(SuggestionKind.demerger, SuggestionStatus.auto_applied);
        UUID user = UUID.randomUUID();
        com.financeos.core.security.UserContext.setCurrentUserId(user);
        try {
            when(repo.findPendingUnresolved(user)).thenReturn(List.of(pending, merger));
            when(repo.findOwnedByStatus(user, SuggestionStatus.auto_applied)).thenReturn(List.of(applied));
            when(inv.openPositionsBefore(any())).thenReturn(Map.of());
            when(rule.unanswered(user)).thenReturn(List.of());
            when(finder.find("ITC Limited", "ITC", ex)).thenReturn(List.of(
                    new ChildCandidate("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), null)));
            when(instruments.findByNseSymbolIn(any())).thenReturn(List.of());
            var service = new CorporateActionReviewService(repo, inv, rule, prices, candidates);
            var review = service.review();
            assertThat(review.needsReview().get(0).possibleChildren()).hasSize(1);
            assertThat(review.needsReview().get(1).possibleChildren()).isEmpty();
            assertThat(review.appliedAutomatically().get(0).possibleChildren()).isEmpty();
        } finally {
            com.financeos.core.security.UserContext.clear();
        }
    }
}
