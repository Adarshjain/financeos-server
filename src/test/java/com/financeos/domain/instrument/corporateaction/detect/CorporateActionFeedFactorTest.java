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

class CorporateActionFeedFactorTest {

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
    void matchedFactorIsStoredOnTheAutoAppliedSuggestion() {
        cleanBonus(EX);
        yahooSplits(EX, "2");

        service.detectForUser(userId);

        ArgumentCaptor<CorporateActionSuggestion> s = ArgumentCaptor.forClass(CorporateActionSuggestion.class);
        verify(suggestions).save(s.capture());
        assertThat(s.getValue().getStatus()).isEqualTo(SuggestionStatus.auto_applied);
        assertThat(s.getValue().getFeedFactor()).isEqualByComparingTo("2");
    }

    @Test
    void mismatchedFactorIsStoredOnThePendingSuggestion() {
        cleanBonus(EX);
        yahooSplits(EX, "3");

        service.detectForUser(userId);

        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getReason()).isEqualTo(SuggestionReason.YAHOO_MISMATCH);
            assertThat(s.getFeedFactor()).isEqualByComparingTo("3");
        });
    }

    @Test
    void missingFeedSplitStoresNoFactor() {
        cleanBonus(EX);
        yahooSplits(EX.plusDays(1), "2");

        service.detectForUser(userId);

        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getReason()).isEqualTo(SuggestionReason.YAHOO_MISSING);
            assertThat(s.getFeedFactor()).isNull();
        });
    }

    @Test
    void feedNotConsultedStoresNoFactor() {
        acme.setYahooSymbol(null);
        cleanBonus(EX);

        service.detectForUser(userId);

        assertThat(savedSuggestions()).singleElement().satisfies(s -> {
            assertThat(s.getReason()).isEqualTo(SuggestionReason.NO_PRICE_SYMBOL);
            assertThat(s.getFeedFactor()).isNull();
        });
    }
}
