package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.price.PriceHistoryClient;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OversoldSuggestionServiceTest {

    private final InvestmentService investmentService = mock(InvestmentService.class);
    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OversoldSuggestionService service = new OversoldSuggestionService(investmentService, suggestions, users);
    private final UUID userId = UUID.randomUUID();
    private final List<CorporateActionSuggestion> existing = new ArrayList<>();
    private final List<InvestmentService.HoldingDetail> details = new ArrayList<>();

    OversoldSuggestionServiceTest() {
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(suggestions.findAllOwned(userId)).thenReturn(existing);
        when(investmentService.getHoldingDetails(null)).thenReturn(details);
    }

    private static Instrument instrument(InstrumentType type) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName("Acme Ltd");
        i.setType(type);
        return i;
    }

    private void position(Instrument instrument, String oversoldQty, LocalDate oversoldDate) {
        Holding h = new Holding(new Account(), instrument, null);
        h.setId(UUID.randomUUID());
        HoldingPosition p = new HoldingPosition(h, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null,
                null, null, null, null, null, null, null, null, false,
                oversoldQty == null ? null : new BigDecimal(oversoldQty), oversoldDate);
        details.add(new InvestmentService.HoldingDetail(p, null, List.of(), List.of(), null, List.of()));
    }

    private CorporateActionSuggestion row(Instrument instrument, LocalDate exDate, SuggestionStatus status) {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(instrument);
        s.setKind(SuggestionKind.oversold);
        s.setExDate(exDate);
        s.setStatus(status);
        existing.add(s);
        return s;
    }

    @Test
    void anOversoldStockGetsOnePendingRowOnTheEarliestFirstSellDate() {
        Instrument acme = instrument(InstrumentType.stock);
        position(acme, "5", LocalDate.of(2024, 3, 1));
        position(acme, "2", LocalDate.of(2024, 2, 1));
        position(instrument(InstrumentType.stock), null, null);
        position(instrument(InstrumentType.mutual_fund), "4", LocalDate.of(2024, 1, 1));

        service.sync(userId);

        ArgumentCaptor<List<CorporateActionSuggestion>> saved = ArgumentCaptor.forClass(List.class);
        verify(suggestions).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        CorporateActionSuggestion s = saved.getValue().get(0);
        assertThat(s.getInstrument()).isSameAs(acme);
        assertThat(s.getKind()).isEqualTo(SuggestionKind.oversold);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.pending);
        assertThat(s.getExDate()).isEqualTo(LocalDate.of(2024, 2, 1));
    }

    @Test
    void aRowOfAnyStatusForTheKeyIsNeverRaisedAgain() {
        Instrument acme = instrument(InstrumentType.stock);
        position(acme, "5", LocalDate.of(2024, 2, 1));
        row(acme, LocalDate.of(2024, 2, 1), SuggestionStatus.dismissed);

        service.sync(userId);

        verify(suggestions, never()).saveAll(any());
        verify(suggestions, never()).deleteAll(any());
    }

    @Test
    void aPendingRowOfAnInstrumentNoLongerOversoldIsRemovedButADismissedOneStays() {
        Instrument gone = instrument(InstrumentType.stock);
        Instrument dismissed = instrument(InstrumentType.stock);
        CorporateActionSuggestion pending = row(gone, LocalDate.of(2024, 2, 1), SuggestionStatus.pending);
        row(dismissed, LocalDate.of(2024, 2, 1), SuggestionStatus.dismissed);
        position(gone, null, null);

        service.sync(userId);

        ArgumentCaptor<List<CorporateActionSuggestion>> removed = ArgumentCaptor.forClass(List.class);
        verify(suggestions).deleteAll(removed.capture());
        assertThat(removed.getValue()).containsExactly(pending);
        verify(suggestions, never()).saveAll(any());
    }

    @Test
    void aPendingRowWhoseFirstDateMovedIsReplaced() {
        Instrument acme = instrument(InstrumentType.stock);
        CorporateActionSuggestion old = row(acme, LocalDate.of(2024, 5, 1), SuggestionStatus.pending);
        position(acme, "5", LocalDate.of(2024, 2, 1));

        service.sync(userId);

        verify(suggestions).deleteAll(List.of(old));
        ArgumentCaptor<List<CorporateActionSuggestion>> saved = ArgumentCaptor.forClass(List.class);
        verify(suggestions).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getExDate()).isEqualTo(LocalDate.of(2024, 2, 1));
    }

    @Test
    void positionsAreComputedOnce() {
        position(instrument(InstrumentType.stock), "5", LocalDate.of(2024, 2, 1));
        position(instrument(InstrumentType.stock), "5", LocalDate.of(2024, 2, 1));

        service.sync(userId);

        verify(investmentService, org.mockito.Mockito.times(1)).getHoldingDetails(null);
    }

    @Test
    void aFailureIsSwallowed() {
        when(investmentService.getHoldingDetails(null)).thenThrow(new IllegalStateException("boom"));

        service.sync(userId);

        verify(suggestions, never()).saveAll(any());
    }

    @Test
    void detectionRunsTheOversoldCheckEvenWithoutStockTradesAndItsFailureDoesNotFailTheRun() {
        OversoldSuggestionService check = mock(OversoldSuggestionService.class);
        doThrow(new IllegalStateException("boom")).when(check).sync(userId);
        CorporateActionDetectionService detection = new CorporateActionDetectionService(
                mock(InvestmentTransactionRepository.class), mock(MarketEventStore.class), mock(MarketCorporateEventRepository.class),
                mock(CorporateActionRepository.class), suggestions, mock(PriceHistoryClient.class),
                mock(CorporateActionService.class), users, new TransactionTemplate(mock(PlatformTransactionManager.class)), check);

        assertThat(detection.detectForUser(userId)).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 0));
        verify(check).sync(userId);
    }
}
