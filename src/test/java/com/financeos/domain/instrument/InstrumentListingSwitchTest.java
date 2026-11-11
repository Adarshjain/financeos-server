package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The NSE <-> BSE switch of {@link InstrumentRepointService#switchListing}: which row the user lands on, the
 * price check before a new row, and that only this user's references (and detected suggestions) move.
 */
class InstrumentListingSwitchTest {

    private static final String ISIN = "INE002A01018";
    private static final String YAHOO_BO = "RELIANCE.BO";
    private final UUID user = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID broker = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private InstrumentRepository repo;
    private InstrumentSearchService search;
    private InstrumentRepointMap repointMap;
    private ApplicationEventPublisher events;
    private InstrumentRepointService service;
    private Instrument nse;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=Oracle;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        for (String ddl : List.of(
                "CREATE TABLE holdings (id VARCHAR(36), user_id VARCHAR(36), broker_account_id VARCHAR(36), instrument_id VARCHAR(36), notes VARCHAR(100))",
                "CREATE TABLE investment_transactions (id VARCHAR(36), holding_id VARCHAR(36))",
                "CREATE TABLE dividends (id VARCHAR(36), holding_id VARCHAR(36))",
                "CREATE TABLE trade_settlement_classifications (id VARCHAR(36), holding_id VARCHAR(36), user_id VARCHAR(36), instrument_id VARCHAR(36), broker_account_id VARCHAR(36), trade_date DATE, intraday_qty DECIMAL(20,4), intraday_buy_value DECIMAL(20,4), intraday_sell_value DECIMAL(20,4))",
                "CREATE TABLE corporate_actions (id VARCHAR(36), user_id VARCHAR(36), instrument_id VARCHAR(36), target_instrument_id VARCHAR(36))",
                "CREATE TABLE instrument_prices (id VARCHAR(36), user_id VARCHAR(36), instrument_id VARCHAR(36), as_of DATE)",
                "CREATE TABLE instrument_aliases (id VARCHAR(36), instrument_id VARCHAR(36), old_symbol VARCHAR(100), old_name VARCHAR(255), source VARCHAR(30), user_id VARCHAR(36), created_at TIMESTAMP)",
                "CREATE TABLE corporate_action_suggestions (id VARCHAR(36), user_id VARCHAR(36), instrument_id VARCHAR(36), kind VARCHAR(20), ex_date DATE)")) {
            jdbc.execute(ddl);
        }
        repo = mock(InstrumentRepository.class);
        search = mock(InstrumentSearchService.class);
        repointMap = mock(InstrumentRepointMap.class);
        events = mock(ApplicationEventPublisher.class);
        AssetClassOverrideService overrides = mock(AssetClassOverrideService.class);
        InvestmentService investment = mock(InvestmentService.class);
        when(investment.figuresOf(any())).thenReturn(List.of());
        service = new InstrumentRepointService(search, repo, overrides, jdbc, mock(EntityManager.class), events,
                mock(CorporateActionRepository.class), repointMap, investment);
        nse = row("NSE", "RELIANCE", ISIN, "RELIANCE.NS");
    }

    private static Instrument row(String exchange, String symbol, String isin, String yahoo) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName("Reliance Industries");
        i.setSymbol(symbol);
        i.setExchange(exchange);
        i.setIsin(isin);
        i.setYahooSymbol(yahoo);
        i.setCurrency("INR");
        return i;
    }

    private static InstrumentRequest asking(Instrument i, String exchange) {
        return new InstrumentRequest(i.getType(), i.getName(), i.getSymbol(), exchange, i.getIsin(), i.getAmfiCode(),
                i.getYahooSymbol(), i.getCurrency());
    }

    private void holding(UUID owner, Instrument on) {
        jdbc.update("INSERT INTO holdings (id, user_id, broker_account_id, instrument_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), owner.toString(), broker.toString(), on.getId().toString());
    }

    private List<String> holdingInstruments(UUID owner) {
        return jdbc.queryForList("SELECT instrument_id FROM holdings WHERE user_id = ?", String.class, owner.toString());
    }

    private Instrument newBseRow() {
        Instrument bse = row("BSE", "RELIANCE", ISIN, "RELIANCE.BO");
        when(search.addRow(any())).thenReturn(bse);
        return bse;
    }

    @Test
    void switchingToBseCreatesTheRowWithTheSameIsinAndMovesOnlyThisUsersHolding() {
        holding(user, nse);
        holding(otherUser, nse);
        Instrument bse = newBseRow();

        InstrumentRepointService.Result result = service.switchListing(user, nse, asking(nse, " bse "), YAHOO_BO);

        assertTrue(result.created());
        assertEquals(bse, result.target());
        ArgumentCaptor<ResolveInstrumentRequest> req = ArgumentCaptor.forClass(ResolveInstrumentRequest.class);
        verify(search).addRow(req.capture());
        assertEquals(ISIN, req.getValue().isin());
        assertEquals("BSE", req.getValue().exchange());
        assertEquals("RELIANCE.BO", req.getValue().yahooSymbol());
        assertEquals("RELIANCE", req.getValue().symbol());
        assertEquals("Reliance Industries", req.getValue().name());
        assertEquals(InstrumentType.stock, req.getValue().type());
        assertEquals("INR", req.getValue().currency());
        assertEquals(List.of(bse.getId().toString()), holdingInstruments(user));
        assertEquals(List.of(nse.getId().toString()), holdingInstruments(otherUser));
        verify(repointMap).record(user, nse.getId(), bse.getId());
        verify(events).publishEvent(new PriceRefreshEvent(Set.of(bse.getId())));
    }

    @Test
    void switchingBackLandsOnTheOriginalRow() {
        Instrument bse = row("BSE", "RELIANCE", ISIN, "RELIANCE.BO");
        holding(user, bse);
        when(repo.findByIsinAndExchange(ISIN, "NSE")).thenReturn(Optional.of(nse));

        InstrumentRepointService.Result result = service.switchListing(user, bse, asking(bse, "NSE"), null);

        assertFalse(result.created());
        assertEquals(nse, result.target());
        assertEquals(List.of(nse.getId().toString()), holdingInstruments(user));
        verify(repointMap).record(user, bse.getId(), nse.getId());
        verify(search, never()).addRow(any());
    }

    @Test
    void switchingOntoAnExistingBseRowNeedsNoVerifiedSymbolAndMergesAtTheSameBroker() {
        Instrument bse = row("BSE", "RELIANCE", ISIN, "RELIANCE.BO");
        holding(user, nse);
        holding(user, bse);
        when(repo.findByIsinAndExchange(ISIN, "BSE")).thenReturn(Optional.of(bse));

        InstrumentRepointService.Result result = service.switchListing(user, nse, asking(nse, "BSE"), null);

        assertTrue(result.merged());
        assertEquals(List.of(bse.getId().toString()), holdingInstruments(user));
    }

    @Test
    void anIsinLessRowWithTheSameTickerIsAdoptedAndGetsTheIsin() {
        holding(user, nse);
        Instrument bare = row("BSE", "RELIANCE", null, "reliance.bo");
        when(repo.findBySymbolAndExchange("RELIANCE", "BSE")).thenReturn(Optional.of(bare));
        when(repo.save(bare)).thenReturn(bare);

        InstrumentRepointService.Result result = service.switchListing(user, nse, asking(nse, "BSE"), YAHOO_BO);

        assertFalse(result.created());
        assertEquals(bare, result.target());
        assertEquals(ISIN, bare.getIsin());
        assertEquals(List.of(bare.getId().toString()), holdingInstruments(user));
        verify(search, never()).addRow(any());
    }

    @Test
    void aTickerOwnedByAnotherIsinGivesTheNewRowNoSymbolAndKeepsTheUsersTicker() {
        holding(user, nse);
        newBseRow();
        when(repo.findBySymbolAndExchange("RELIANCE", "BSE")).thenReturn(
                Optional.of(row("BSE", "RELIANCE", "INE999Z01010", "RELIANCE.BO")));
        when(repo.existsTickerIgnoreCase("RELIANCE", "BSE")).thenReturn(true);

        service.switchListing(user, nse, asking(nse, "BSE"), YAHOO_BO);

        ArgumentCaptor<ResolveInstrumentRequest> req = ArgumentCaptor.forClass(ResolveInstrumentRequest.class);
        verify(search).addRow(req.capture());
        assertEquals(null, req.getValue().symbol());
    }

    @Test
    void aSwitchOntoAMissingRowWithoutAVerifiedSymbolRefusesAndChangesNothing() {
        holding(user, nse);

        assertThrows(ValidationException.class, () -> service.switchListing(user, nse, asking(nse, "BSE"), null));

        assertUntouched();
    }

    @Test
    void anIsinLessRowWithAnotherTypeOrYahooSymbolIsNotAdoptedAndANewRowIsMade() {
        holding(user, nse);
        newBseRow();
        Instrument otherYahoo = row("BSE", "RELIANCE", null, "500325.BO");
        when(repo.findBySymbolAndExchange("RELIANCE", "BSE")).thenReturn(Optional.of(otherYahoo));
        when(repo.existsTickerIgnoreCase("RELIANCE", "BSE")).thenReturn(true);

        InstrumentRepointService.Result result = service.switchListing(user, nse, asking(nse, "BSE"), YAHOO_BO);

        assertTrue(result.created());
        assertEquals(null, otherYahoo.getIsin());
        verify(repo, never()).save(otherYahoo);

        Instrument otherType = row("BSE", "RELIANCE", null, YAHOO_BO);
        otherType.setType(InstrumentType.etf);
        assertFalse(InstrumentRepointService.isAdoptable(otherType, nse, YAHOO_BO));
        assertTrue(InstrumentRepointService.isAdoptable(row("BSE", "RELIANCE", null, "Reliance.BO"), nse, YAHOO_BO));
        assertFalse(InstrumentRepointService.isAdoptable(row("BSE", "RELIANCE", ISIN, YAHOO_BO), nse, YAHOO_BO));
    }

    @Test
    void theOtherListingsYahooSymbolSwapsTheSuffixElseAppendsOne() {
        assertEquals("RELIANCE.BO", InstrumentRepointService.listingYahooSymbol(nse, "BSE"));
        Instrument bse = row("BSE", "RELIANCE", ISIN, "RELIANCE.bo");
        assertEquals("RELIANCE.NS", InstrumentRepointService.listingYahooSymbol(bse, "NSE"));
        Instrument dotted = row("NSE", "X.NS", ISIN, "X.NS.NS");
        assertEquals("X.NS.BO", InstrumentRepointService.listingYahooSymbol(dotted, "BSE"));
        Instrument noSuffix = row("NSE", "TCS", ISIN, "TCS");
        assertEquals("TCS.BO", InstrumentRepointService.listingYahooSymbol(noSuffix, "BSE"));
        Instrument noYahoo = row("NSE", "TCS", ISIN, null);
        assertEquals("TCS.BO", InstrumentRepointService.listingYahooSymbol(noYahoo, "BSE"));
        Instrument symbolEndsInSuffix = row("NSE", "X.NS", ISIN, null);
        assertEquals("X.NS.BO", InstrumentRepointService.listingYahooSymbol(symbolEndsInSuffix, "BSE"));
    }

    private void assertUntouched() {
        assertEquals(List.of(nse.getId().toString()), holdingInstruments(user));
        verify(search, never()).addRow(any());
        verify(repo, never()).save(any());
        verify(repointMap, never()).record(any(), any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static final DisplayChanges EXCHANGE_CHANGED = new DisplayChanges(false, false, true, false, false);

    @Test
    void onlyAnNseBseSwitchOfAStockWithAnIsinThatChangesWhatTheUserSeesIsASwitch() {
        assertTrue(InstrumentRepointService.isListingSwitch(nse, asking(nse, "BSE"), EXCHANGE_CHANGED));
        assertTrue(InstrumentRepointService.isListingSwitch(nse, asking(nse, " bse "), EXCHANGE_CHANGED));
        assertFalse(InstrumentRepointService.isListingSwitch(nse, asking(nse, "BSE"), DisplayChanges.NONE),
                "the user already sees BSE (a legacy override): no move");
        assertFalse(InstrumentRepointService.isListingSwitch(nse, asking(nse, "NSE"), EXCHANGE_CHANGED),
                "the catalog exchange again only clears the override");
        assertFalse(InstrumentRepointService.isListingSwitch(nse, asking(nse, "NYSE"), EXCHANGE_CHANGED));
        assertFalse(InstrumentRepointService.isListingSwitch(nse, asking(nse, ""), EXCHANGE_CHANGED));
        assertFalse(InstrumentRepointService.isListingSwitch(nse, asking(nse, null), EXCHANGE_CHANGED));
        Instrument noIsin = row("NSE", "XYZ", null, "XYZ.NS");
        assertFalse(InstrumentRepointService.isListingSwitch(noIsin, asking(noIsin, "BSE"), EXCHANGE_CHANGED));
        Instrument other = row("NYSE", "XYZ", ISIN, null);
        assertFalse(InstrumentRepointService.isListingSwitch(other, asking(other, "BSE"), EXCHANGE_CHANGED));
    }

    @Test
    void suggestionsMoveWithTheRepointAndAClashingOneOnTheTargetIsDropped() {
        holding(user, nse);
        Instrument bse = newBseRow();
        String from = nse.getId().toString();
        String to = bse.getId().toString();
        insertSuggestion(user, from, "split", "2026-03-01");
        insertSuggestion(user, from, "bonus", "2026-04-01");
        insertSuggestion(user, to, "split", "2026-03-01");
        insertSuggestion(otherUser, from, "split", "2026-03-01");

        service.switchListing(user, nse, asking(nse, "BSE"), YAHOO_BO);

        assertEquals(2, count(user, to), "the clash is dropped, the other one moved, the target's own stays");
        assertEquals(0, count(user, from));
        assertEquals(1, count(otherUser, from), "another user's suggestion stays");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM corporate_action_suggestions WHERE user_id = ? "
                + "AND instrument_id = ? AND kind = 'bonus'", Integer.class, user.toString(), to));
    }

    @Test
    void theIdentifierPathMovesSuggestionsToo() {
        Instrument target = row("NSE", "OTHER", "INE123A01010", "OTHER.NS");
        insertSuggestion(user, nse.getId().toString(), "split", "2026-03-01");

        service.moveSuggestions(user.toString(), nse.getId().toString(), target.getId().toString());

        assertEquals(1, count(user, target.getId().toString()));
        assertEquals(0, count(user, nse.getId().toString()));
    }

    private void insertSuggestion(UUID owner, String instrument, String kind, String exDate) {
        jdbc.update("INSERT INTO corporate_action_suggestions (id, user_id, instrument_id, kind, ex_date) "
                + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), owner.toString(), instrument, kind,
                java.sql.Date.valueOf(exDate));
    }

    private int count(UUID owner, String instrument) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM corporate_action_suggestions WHERE user_id = ? "
                + "AND instrument_id = ?", Integer.class, owner.toString(), instrument);
    }
}
