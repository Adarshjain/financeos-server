package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.instrument.price.PriceHistoryClient;
import com.financeos.domain.instrument.price.PriceHistoryClient.SplitResult;
import com.financeos.domain.instrument.price.PriceHistoryClient.SplitStatus;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CorporateActionDetectionServiceTest {

    private static final LocalDate BUY = LocalDate.of(2024, 1, 10);
    private static final LocalDate EX = LocalDate.of(2024, 10, 28);

    private final InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
    private final MarketEventStore eventStore = mock(MarketEventStore.class);
    private final MarketCorporateEventRepository events = mock(MarketCorporateEventRepository.class);
    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final PriceHistoryClient priceHistory = mock(PriceHistoryClient.class);
    private final CorporateActionService actionService = mock(CorporateActionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CorporateActionDetectionService service = new CorporateActionDetectionService(transactions, eventStore,
            events, actions, suggestions, priceHistory, actionService, users,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private final UUID userId = UUID.randomUUID();
    private Instrument acme;
    private final List<InvestmentTransaction> trades = new ArrayList<>();
    private final List<MarketCorporateEvent> feed = new ArrayList<>();
    private final List<CorporateActionSuggestion> existingSuggestions = new ArrayList<>();
    private final List<CorporateAction> existingActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        acme = instrument("ACMEIND", "INE000X01011", "ACMEIND.NS");
        trade(acme, InvestmentTransactionType.buy, "10", BUY);
        when(transactions.findStockTradesOwned(userId)).thenReturn(trades);
        when(events.findFrom(eq("nse"), any())).thenReturn(feed);
        when(actions.findOwnedByInstrumentIds(eq(userId), any())).thenReturn(existingActions);
        when(suggestions.findAllOwned(userId)).thenReturn(existingSuggestions);
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(actionService.createDetected(any(), any(), any(), anyInt(), anyInt(), any(), anyString()))
                .thenAnswer(i -> new CorporateAction());
    }

    private Instrument instrument(String symbol, String isin, String yahoo) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName(symbol);
        i.setSymbol(symbol);
        i.setIsin(isin);
        i.setYahooSymbol(yahoo);
        return i;
    }

    private void trade(Instrument instrument, InvestmentTransactionType type, String qty, LocalDate date) {
        Holding h = new Holding();
        h.setInstrument(instrument);
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setTradeDate(date);
        trades.add(t);
    }

    private MarketCorporateEvent event(String symbol, String isin, LocalDate exDate, String subject, String type,
                                       Integer from, Integer to, boolean clean) {
        MarketCorporateEvent e = new MarketCorporateEvent();
        e.setSource("nse");
        e.setSymbol(symbol);
        e.setIsin(isin);
        e.setExDate(exDate);
        e.setSubject(subject);
        e.setParsedType(type);
        e.setRatioFrom(from);
        e.setRatioTo(to);
        e.setClean(clean);
        feed.add(e);
        return e;
    }

    private MarketCorporateEvent cleanBonus(LocalDate exDate) {
        return event("ACMEIND", "INE000X01011", exDate, "Bonus 1:1", "bonus", 1, 2, true);
    }

    private void yahooSplits(LocalDate day, String factor) {
        TreeMap<LocalDate, BigDecimal> m = new TreeMap<>();
        if (day != null) {
            m.put(day, new BigDecimal(factor));
        }
        when(priceHistory.fetchYahooSplits(eq("ACMEIND.NS"), any(), any())).thenReturn(new SplitResult(SplitStatus.OK, m));
    }

    @SuppressWarnings("unchecked")
    private List<CorporateActionSuggestion> savedSuggestions() {
        ArgumentCaptor<List<CorporateActionSuggestion>> c = ArgumentCaptor.forClass(List.class);
        verify(suggestions).saveAll(c.capture());
        return c.getValue();
    }

    @Test
    void agreeingSourcesApplyTheAction() {
        cleanBonus(EX);
        yahooSplits(EX, "2");

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(1, 0, 0));
        verify(actionService).createDetected(userId, acme.getId(), CorporateActionType.bonus, 1, 2, EX,
                "Detected automatically: Bonus 1:1");
        ArgumentCaptor<CorporateActionSuggestion> s = ArgumentCaptor.forClass(CorporateActionSuggestion.class);
        verify(suggestions).save(s.capture());
        assertThat(s.getValue().getStatus()).isEqualTo(SuggestionStatus.auto_applied);
        assertThat(s.getValue().getKind()).isEqualTo(SuggestionKind.bonus);
        assertThat(s.getValue().getCorporateAction()).isNotNull();
        verify(eventStore).ensureCoverage(BUY);
    }

    @Test
    void splitIsAppliedWithItsRatio() {
        event("ACMEIND", "INE000X01011", EX, "Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 2/- Per Share",
                "split", 1, 5, true);
        yahooSplits(EX, "5");

        service.detectForUser(userId);

        verify(actionService).createDetected(eq(userId), eq(acme.getId()), eq(CorporateActionType.split), eq(1), eq(5), eq(EX), anyString());
    }

    @Test
    void missingYahooEventHoldsAsPending() {
        cleanBonus(EX);
        yahooSplits(null, null);

        var summary = service.detectForUser(userId);

        assertThat(summary.applied()).isZero();
        assertThat(summary.pending()).isEqualTo(1);
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.pending);
            assertThat(s.getReason()).isEqualTo(SuggestionReason.YAHOO_MISSING);
        });
    }

    @Test
    void yahooOnAnotherDayIsMissing() {
        cleanBonus(EX);
        yahooSplits(EX.plusDays(1), "2");
        service.detectForUser(userId);
        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.YAHOO_MISSING);
    }

    @Test
    void differentFactorIsAMismatch() {
        cleanBonus(EX);
        yahooSplits(EX, "3");

        service.detectForUser(userId);

        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.YAHOO_MISMATCH);
    }

    @Test
    void noPriceSymbolHoldsAsPending() {
        acme.setYahooSymbol(null);
        cleanBonus(EX);
        service.detectForUser(userId);
        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.NO_PRICE_SYMBOL);
    }

    @Test
    void symbolNotFoundAtYahooHoldsAsPending() {
        cleanBonus(EX);
        when(priceHistory.fetchYahooSplits(anyString(), any(), any())).thenReturn(new SplitResult(SplitStatus.NOT_FOUND, new TreeMap<>()));
        service.detectForUser(userId);
        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.NO_PRICE_SYMBOL);
    }

    @Test
    void rateLimitedOrFailedYahooCreatesNothing() {
        cleanBonus(EX);
        for (SplitStatus status : new SplitStatus[] {SplitStatus.RATE_LIMITED, SplitStatus.FAILED}) {
            when(priceHistory.fetchYahooSplits(anyString(), any(), any())).thenReturn(new SplitResult(status, new TreeMap<>()));
            var summary = service.detectForUser(userId);
            assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 0));
        }
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(suggestions, never()).save(any());
        verify(suggestions, never()).saveAll(any());
    }

    @Test
    void twoSameDayEventsAreBothPendingAndNoneApplied() {
        cleanBonus(EX);
        event("ACMEIND", "INE000X01011", EX, "Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 2/- Per Share",
                "split", 1, 5, true);

        var summary = service.detectForUser(userId);

        assertThat(summary.applied()).isZero();
        assertThat(summary.pending()).isEqualTo(2);
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(priceHistory, never()).fetchYahooSplits(any(), any(), any());
        assertThat(savedSuggestions()).hasSize(2)
                .allSatisfy(s -> assertThat(s.getReason()).isEqualTo(SuggestionReason.COMBINED_SAME_DAY));
    }

    @Test
    void demergerNeedsDetails() {
        event("ACMEIND", "INE000X01011", EX, "Demerger", "demerger", null, null, false);
        service.detectForUser(userId);
        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(SuggestionKind.demerger);
            assertThat(s.getReason()).isEqualTo(SuggestionReason.NEEDS_DETAILS);
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.pending);
        });
    }

    @Test
    void unclearTextKeepsParsedRatiosAsPending() {
        event("ACMEIND", "INE000X01011", EX, "Consolidation Of Equity Shares From Re 1 Per Share To Rs 10 Per Share",
                "split", 10, 1, false);
        service.detectForUser(userId);
        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getReason()).isEqualTo(SuggestionReason.UNCLEAR_TEXT);
            assertThat(s.getRatioFrom()).isEqualTo(10);
            assertThat(s.getRatioTo()).isEqualTo(1);
        });
    }

    @Test
    void ignoredWhenNothingHeldTheDayBefore() {
        // sold out before the ex-date
        trade(acme, InvestmentTransactionType.sell, "10", EX.minusDays(30));
        cleanBonus(EX);
        var summary = service.detectForUser(userId);
        assertThat(summary.applied() + summary.pending()).isZero();
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(priceHistory, never()).fetchYahooSplits(any(), any(), any());
    }

    @Test
    void ignoredWhenFirstBuyIsAfterTheExDate() {
        trades.clear();
        trade(acme, InvestmentTransactionType.buy, "10", EX.plusDays(5));
        cleanBonus(EX);
        var summary = service.detectForUser(userId);
        assertThat(summary.applied() + summary.pending()).isZero();
        verify(suggestions, never()).saveAll(any());
    }

    @Test
    void buyOnTheExDateIsNotHeldBeforeIt() {
        trades.clear();
        trade(acme, InvestmentTransactionType.buy, "10", EX);
        cleanBonus(EX);
        assertThat(service.detectForUser(userId).applied()).isZero();
    }

    @Test
    void existingActionOnTheDaySkipsEvenOfAnotherType() {
        CorporateAction ca = new CorporateAction();
        ca.setInstrument(acme);
        ca.setType(CorporateActionType.split);
        ca.setExDate(EX);
        existingActions.add(ca);
        cleanBonus(EX);

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 1));
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void dismissedSuggestionIsNotRecreated() {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setInstrument(acme);
        s.setKind(SuggestionKind.bonus);
        s.setExDate(EX);
        s.setStatus(SuggestionStatus.dismissed);
        existingSuggestions.add(s);
        cleanBonus(EX);
        yahooSplits(EX, "2");

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 1));
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(suggestions, never()).save(any());
    }

    @Test
    void renamedSymbolMatchesByIsinPrefix() {
        // the feed knows the company under its new symbol and a stale-suffix ISIN
        event("ACMENEW", "INE000X01029", EX, "Bonus 1:1", "bonus", 1, 2, true);
        yahooSplits(EX, "2");

        var summary = service.detectForUser(userId);

        assertThat(summary.applied()).isEqualTo(1);
    }

    @Test
    void isinPrefixSharedByTwoSymbolsIsNotMatched() {
        event("ACMENEW", "INE000X01029", EX, "Bonus 1:1", "bonus", 1, 2, true);
        event("OTHERCO", "INE000X01037", EX, "Bonus 1:1", "bonus", 1, 2, true);
        yahooSplits(EX, "2");

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 0));
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void noStockTradesDoesNothing() {
        trades.clear();
        var summary = service.detectForUser(userId);
        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 0));
        verify(eventStore, never()).ensureCoverage(any());
    }

    @Test
    void yahooIsFetchedOncePerInstrument() {
        cleanBonus(EX);
        cleanBonus(EX.plusDays(100));
        yahooSplits(EX, "2");
        service.detectForUser(userId);
        verify(priceHistory).fetchYahooSplits(eq("ACMEIND.NS"), eq(BUY), any());
    }

    @Test
    void futureExDateIsSkippedWithNoRow() {
        cleanBonus(com.financeos.core.time.AppTime.today().plusDays(3));
        yahooSplits(null, null);

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 0));
        verify(suggestions, never()).saveAll(any());
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void recentExDateWithNoYahooEventRetriesLater() {
        cleanBonus(com.financeos.core.time.AppTime.today().minusDays(1));
        yahooSplits(null, null);

        var summary = service.detectForUser(userId);

        assertThat(summary.pending()).isZero();
        verify(suggestions, never()).saveAll(any());
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void recentExDateWithADifferentYahooFactorIsStillAMismatch() {
        LocalDate recent = com.financeos.core.time.AppTime.today().minusDays(1);
        cleanBonus(recent);
        yahooSplits(recent, "3");

        service.detectForUser(userId);

        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.YAHOO_MISMATCH);
    }

    @Test
    void handEnteredSameRatioNearbyIsHeldAsPossibleDuplicate() {
        cleanBonus(EX);
        yahooSplits(EX, "2");
        CorporateAction mine = new CorporateAction();
        mine.setInstrument(acme);
        mine.setType(CorporateActionType.bonus);
        mine.setRatioFrom(1);
        mine.setRatioTo(2);
        mine.setExDate(EX.minusDays(4));
        existingActions.add(mine);

        var summary = service.detectForUser(userId);

        assertThat(summary.applied()).isZero();
        verify(actionService, never()).createDetected(any(), any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(savedSuggestions()).singleElement().extracting(CorporateActionSuggestion::getReason)
                .isEqualTo(SuggestionReason.POSSIBLE_DUPLICATE);
    }

    @Test
    void handEnteredActionBeyondFiveDaysOrOtherRatioDoesNotBlock() {
        cleanBonus(EX);
        yahooSplits(EX, "2");
        CorporateAction far = new CorporateAction();
        far.setInstrument(acme);
        far.setType(CorporateActionType.bonus);
        far.setRatioFrom(1);
        far.setRatioTo(2);
        far.setExDate(EX.minusDays(6));
        existingActions.add(far);
        CorporateAction other = new CorporateAction();
        other.setInstrument(acme);
        other.setType(CorporateActionType.bonus);
        other.setRatioFrom(2);
        other.setRatioTo(3);
        other.setExDate(EX.minusDays(1));
        existingActions.add(other);

        assertThat(service.detectForUser(userId).applied()).isEqualTo(1);
    }

    @Test
    void concurrentApplyConflictIsSkippedAndTheRunContinues() {
        cleanBonus(EX);
        yahooSplits(EX, "2");
        when(suggestions.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        var summary = service.detectForUser(userId);

        assertThat(summary).isEqualTo(new CorporateActionDetectionService.Summary(0, 0, 1));
    }

    @Test
    void concurrentPendingBatchConflictFallsBackToRowByRow() {
        cleanBonus(EX);
        yahooSplits(null, null);
        when(suggestions.saveAll(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        var summary = service.detectForUser(userId);

        assertThat(summary.pending()).isEqualTo(1);
        verify(suggestions).save(any());
    }
}
