package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.price.PriceProvider;
import com.financeos.domain.instrument.price.PriceQuote;
import com.financeos.domain.instrument.search.InstrumentSearchProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InstrumentListingPlanner}: the Yahoo symbol and price check of an NSE <-> BSE switch, run with no
 * transaction open, and refusing (400) with nothing written.
 */
class InstrumentListingPlannerTest {

    private static final String ISIN = "INE002A01018";
    private final UUID user = UUID.randomUUID();
    private InstrumentRepository repo;
    private AssetClassOverrideService overrides;
    private Feed feed;
    private Search search;
    private InstrumentListingPlanner planner;
    private Instrument nse;

    /** Records whether a transaction was open when the feed was asked. */
    private static final class Feed implements PriceProvider {
        String mode = "price";
        final Set<UUID> notFound = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        final List<Boolean> transactionOpen = new ArrayList<>();

        public PriceSource source() {
            return PriceSource.YAHOO;
        }

        public boolean supports(Instrument instrument) {
            return true;
        }

        public Map<UUID, PriceQuote> fetch(List<Instrument> instruments) {
            transactionOpen.add(TransactionSynchronizationManager.isActualTransactionActive());
            asked.add(instruments.get(0).getYahooSymbol());
            notFound.clear();
            if ("price".equals(mode)) {
                return Map.of(instruments.get(0).getId(), new PriceQuote(BigDecimal.TEN, LocalDate.of(2026, 1, 5)));
            }
            if ("notfound".equals(mode)) {
                notFound.add(instruments.get(0).getId());
            }
            return Map.of();
        }

        public Set<UUID> takeNotFound() {
            return notFound;
        }
    }

    private static final class Search implements InstrumentSearchProvider {
        List<InstrumentCandidate> results = List.of();
        final List<Boolean> transactionOpen = new ArrayList<>();

        public boolean supports(InstrumentType type) {
            return true;
        }

        public List<InstrumentCandidate> search(String query, InstrumentType type) {
            transactionOpen.add(TransactionSynchronizationManager.isActualTransactionActive());
            return results;
        }
    }

    @BeforeEach
    void setUp() {
        repo = mock(InstrumentRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        feed = new Feed();
        search = new Search();
        planner = new InstrumentListingPlanner(repo, overrides, List.of(search), List.of(feed));
        nse = new Instrument();
        nse.setId(UUID.randomUUID());
        nse.setType(InstrumentType.stock);
        nse.setName("Reliance Industries");
        nse.setSymbol("RELIANCE");
        nse.setExchange("NSE");
        nse.setIsin(ISIN);
        nse.setYahooSymbol("RELIANCE.NS");
        when(repo.findById(nse.getId())).thenReturn(Optional.of(nse));
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private InstrumentRequest asking(String exchange) {
        return new InstrumentRequest(InstrumentType.stock, nse.getName(), nse.getSymbol(), exchange, ISIN, null,
                nse.getYahooSymbol(), null);
    }

    @Test
    void theFeedsAreAskedWithNoTransactionOpenAndTheSymbolSwapsTheSuffix() {
        assertEquals("RELIANCE.BO", planner.verifiedListingSymbol(nse.getId(), asking("BSE")));

        assertEquals(List.of("RELIANCE.BO"), feed.asked);
        assertEquals(List.of(false), feed.transactionOpen);
        assertEquals(List.of(false), search.transactionOpen);
    }

    @Test
    void aSearchCandidateOnTheRequestedExchangeComesFirst() {
        search.results = List.of(
                new InstrumentCandidate("YAHOO", InstrumentType.stock, "Reliance", "RELIANCE", "NSE", null, null,
                        "RELIANCE.NS", "INR", null, null),
                new InstrumentCandidate("YAHOO", InstrumentType.stock, "Reliance", "RELIANCE", "BSE", null, null,
                        "500325.BO", "INR", null, null));

        assertEquals("500325.BO", planner.verifiedListingSymbol(nse.getId(), asking("BSE")));
        assertEquals(List.of("500325.BO"), feed.asked);
    }

    @Test
    void aRowSymbolEndingInTheSuffixDoesNotGetTwo() {
        nse.setSymbol("X.NS");
        nse.setYahooSymbol(null);

        assertEquals("X.NS.BO", planner.verifiedListingSymbol(nse.getId(), asking("BSE")));
    }

    @Test
    void noPriceRefusesWithTheListingMessage() {
        feed.mode = "notfound";

        ValidationException e = assertThrows(ValidationException.class,
                () -> planner.verifiedListingSymbol(nse.getId(), asking("BSE")));

        assertEquals("No BSE listing with a price was found for Reliance Industries. It stays on NSE.", e.getMessage());
    }

    @Test
    void anUnreachableFeedRefusesWithTheTryLaterMessage() {
        feed.mode = "down";

        ValidationException e = assertThrows(ValidationException.class,
                () -> planner.verifiedListingSymbol(nse.getId(), asking("BSE")));

        assertEquals("Couldn't check the BSE price right now. Try again in a few minutes.", e.getMessage());
    }

    @Test
    void anExistingTargetRowOrANonSwitchNeedsNoCheck() {
        Instrument bse = new Instrument();
        bse.setId(UUID.randomUUID());
        when(repo.findByIsinAndExchange(ISIN, "BSE")).thenReturn(Optional.of(bse));
        assertNull(planner.verifiedListingSymbol(nse.getId(), asking("BSE")));

        assertNull(planner.verifiedListingSymbol(nse.getId(), asking("NYSE")));
        assertNull(planner.verifiedListingSymbol(nse.getId(), asking("NSE")));
        assertTrue(feed.asked.isEmpty());
        assertFalse(search.transactionOpen.contains(true));
    }

    @Test
    void aLegacyOverrideOfTheRequestedExchangeIsNoSwitchSoNoPriceCall() {
        UserInstrumentOverride row = new UserInstrumentOverride(user, nse.getId());
        row.setExchange("BSE");
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.of(List.of(row)));

        assertNull(planner.verifiedListingSymbol(nse.getId(), asking("BSE")));
        assertTrue(feed.asked.isEmpty());
    }

    @Test
    void anIdentifierChangeIsNotThePlannersBusiness() {
        InstrumentRequest changed = new InstrumentRequest(InstrumentType.stock, nse.getName(), "RELIANCE", "BSE",
                "INE999Z01010", null, "RELIANCE.NS", null);
        assertNull(planner.verifiedListingSymbol(nse.getId(), changed));
        assertTrue(feed.asked.isEmpty());
        verifyNoInteractions(overrides);
    }
}
