package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.investment.dto.AcceptSuggestionsRequest;
import com.financeos.api.investment.dto.AcceptSuggestionsResponse;
import com.financeos.api.investment.dto.DividendSuggestionsResponse;
import com.financeos.api.investment.dto.ImportCommitRequest;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ImportPreviewResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAlias;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.price.PriceRefreshEventListener;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendService;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.investment.imports.GrowwStocksParser;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
import com.financeos.domain.investment.imports.ParsedRow;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The dividend scan / accept and the import preview / commit read their holdings, transactions, dividends,
 * instruments and aliases in batches: the answers are what the per-row queries gave (duplicates, alias
 * matches, a holding or instrument created by an earlier row reused by a later one) and the statement
 * count no longer grows with the number of rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DividendImportBatchingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private DividendRepository dividendRepository;
    @Autowired private InvestmentTransactionRepository transactionRepository;
    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentAliasRepository aliasRepository;
    @Autowired private DividendService dividendService;
    @Autowired private ImportService importService;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;

    @MockBean private YahooDividendEventsClient yahoo;
    @MockBean private GrowwStocksParser growwParser;
    @MockBean private InstrumentSearchService searchService;
    @MockBean private PriceRefreshEventListener priceListener;

    private ApiTestClient api;
    private UUID userId;
    private User user;
    private UUID brokerId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "div-import-batch-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        brokerId = api.create("/api/v1/accounts", Map.of("type", "broker", "name", "Batch Broker", "provider", "Groww",
                "clientId", "CL1", "cashBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false));
        when(growwParser.source()).thenReturn(ImportSource.groww);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ------------------------------------------------------------------ fixtures

    private static String uniq() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private Instrument instrument(String symbol, String isin) throws Exception {
        Instrument i = new Instrument();
        i.setType(com.financeos.domain.instrument.InstrumentType.stock);
        i.setName("Batch " + symbol);
        i.setSymbol(symbol);
        i.setExchange("NSE");
        i.setIsin(isin);
        i.setYahooSymbol(symbol + ".NS");
        return instrumentRepository.save(i);
    }

    private Instrument instrument() throws Exception {
        String s = "BT" + uniq();
        return instrument(s, "INE" + uniq() + "1");
    }

    private void buy(Instrument i, String qty, String price, String date) throws Exception {
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", brokerId.toString(),
                "instrumentId", i.getId().toString(), "type", "buy", "quantity", qty, "price", price, "tradeDate", date));
    }

    private Holding holdingOf(Instrument i) {
        return holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerId, i.getId()).orElseThrow();
    }

    private Dividend dividend(Holding h, String amount, LocalDate exDate, LocalDate payDate) {
        Dividend d = new Dividend();
        d.setUser(user);
        d.setHolding(h);
        d.setType(DividendType.dividend);
        d.setAmount(new BigDecimal(amount));
        d.setExDate(exDate);
        d.setPayDate(payDate);
        d.setSource("manual");
        return dividendRepository.save(d);
    }

    /** The HTTP calls of the fixture clear the thread's user; service calls below run as the test user. */
    private void asUser() {
        UserContext.setCurrentUserId(userId);
    }

    private DividendSuggestionsResponse scan(UUID brokerAccountId) {
        asUser();
        return dividendService.scanSuggestions(brokerAccountId);
    }

    private AcceptSuggestionsResponse accept(AcceptSuggestionsRequest request) {
        asUser();
        return dividendService.acceptSuggestions(request);
    }

    private ImportCommitResponse commit(List<ImportCommitRequest.CommitRowDto> rows) {
        asUser();
        return importService.commit(ImportSource.groww, brokerId, rows);
    }

    private long queries(Runnable work) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        jobWorker.pause();
        try {
            stats.clear();
            work.run();
            return stats.getPrepareStatementCount();
        } finally {
            jobWorker.resume();
        }
    }

    // ------------------------------------------------------------------ dividend scan

    private void yahooReturns(Instrument i, List<YahooDividendEventsClient.DividendEvent> events) {
        when(yahoo.fetchDividendEventsWithStatus(eq(i.getYahooSymbol()), any(), any()))
                .thenReturn(new YahooDividendEventsClient.FetchResult(events, true));
    }

    private static YahooDividendEventsClient.DividendEvent ev(String date, String amount) {
        return new YahooDividendEventsClient.DividendEvent(LocalDate.parse(date), new BigDecimal(amount));
    }

    @Test
    void scanSuggestsPerHoldingSkippingRecordedAndNotYetHeld() throws Exception {
        Instrument a = instrument();
        Instrument b = instrument();
        buy(a, "10", "100", "2026-01-05");
        buy(b, "10", "100", "2026-01-05");
        // A's March dividend is already recorded (ex-date 2 days off: within the 7-day window).
        dividend(holdingOf(a), "50", LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-20"));
        yahooReturns(a, List.of(ev("2026-03-10", "5"), ev("2026-06-10", "6")));
        // B's December 2025 event is before it was bought: nothing held, nothing suggested.
        yahooReturns(b, List.of(ev("2026-03-12", "2"), ev("2025-12-01", "9")));

        DividendSuggestionsResponse res = scan(null);

        assertEquals(2, res.suggestions().size());
        DividendSuggestionsResponse.Suggestion first = res.suggestions().get(0);
        DividendSuggestionsResponse.Suggestion second = res.suggestions().get(1);
        assertEquals(holdingOf(a).getId(), first.holdingId());
        assertEquals(LocalDate.parse("2026-06-10"), first.exDate());
        assertEquals(0, new BigDecimal("10").compareTo(first.qtyHeld()));
        assertEquals(0, new BigDecimal("60.00").compareTo(first.estimatedAmount()));
        assertEquals(holdingOf(b).getId(), second.holdingId());
        assertEquals(LocalDate.parse("2026-03-12"), second.exDate());
        assertEquals(0, new BigDecimal("20.00").compareTo(second.estimatedAmount()));
        assertEquals(2, res.scannedSymbols());
        assertTrue(res.skippedSymbols().isEmpty());
    }

    @Test
    void scanQueryCountDoesNotGrowWithHoldingsOrEvents() throws Exception {
        List<Instrument> all = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            Instrument i = instrument();
            buy(i, "10", "100", "2026-01-05");
            yahooReturns(i, List.of(ev("2026-03-10", "5"), ev("2026-06-10", "6")));
            all.add(i);
        }
        // Two holdings first, then all six (the other four are bought but their events are not yet fetched).
        for (int n = 2; n < 6; n++) {
            when(yahoo.fetchDividendEventsWithStatus(eq(all.get(n).getYahooSymbol()), any(), any()))
                    .thenReturn(new YahooDividendEventsClient.FetchResult(List.of(), true));
        }
        long few = queries(() -> scan(null));
        for (int n = 2; n < 6; n++) {
            yahooReturns(all.get(n), List.of(ev("2026-03-10", "5"), ev("2026-06-10", "6")));
        }
        long many = queries(() -> scan(null));

        assertEquals(few, many, "scan statements for 2 vs 6 holdings");
    }

    // ------------------------------------------------------------------ dividend accept

    private AcceptSuggestionsRequest.Item item(UUID holdingId, String exDate) {
        return new AcceptSuggestionsRequest.Item(holdingId, LocalDate.parse(exDate), LocalDate.parse(exDate).plusDays(5),
                new BigDecimal("150.00"), new BigDecimal("15.00"), "n");
    }

    @Test
    void acceptSkipsRecordedAndSameRequestDuplicatesAndCreatesTheRest() throws Exception {
        Instrument a = instrument();
        Instrument b = instrument();
        buy(a, "10", "100", "2026-01-05");
        buy(b, "10", "100", "2026-01-05");
        Holding ha = holdingOf(a);
        Holding hb = holdingOf(b);
        dividend(ha, "50", LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-20"));

        AcceptSuggestionsResponse res = accept(new AcceptSuggestionsRequest(List.of(
                item(ha.getId(), "2026-03-12"),   // within 7 days of the recorded one: skipped
                item(hb.getId(), "2026-03-12"),   // created
                item(hb.getId(), "2026-03-15"),   // within 7 days of the one just created: skipped
                item(hb.getId(), "2026-06-01")))); // created

        assertEquals(2, res.created().size());
        assertEquals(2, res.skippedCount());
        assertEquals(1, dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(ha.getId()).size());
        assertEquals(2, dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(hb.getId()).size());
    }

    @Test
    void acceptOfAnUnknownHoldingIsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class, () -> accept(
                new AcceptSuggestionsRequest(List.of(item(missing, "2026-03-12")))));
    }

    @Test
    void acceptQueryCountDoesNotGrowWithItems() throws Exception {
        Instrument a = instrument();
        buy(a, "10", "100", "2026-01-05");
        Holding ha = holdingOf(a);
        dividend(ha, "50", LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-20"));

        List<AcceptSuggestionsRequest.Item> two = new ArrayList<>();
        List<AcceptSuggestionsRequest.Item> twenty = new ArrayList<>();
        for (int n = 0; n < 20; n++) {
            AcceptSuggestionsRequest.Item it = item(ha.getId(), "2026-03-" + (10 + n % 5));
            twenty.add(it);
            if (n < 2) two.add(it);
        }
        long few = queries(() -> assertEquals(2, accept(new AcceptSuggestionsRequest(two)).skippedCount()));
        long many = queries(() -> assertEquals(20, accept(new AcceptSuggestionsRequest(twenty)).skippedCount()));

        assertEquals(few, many, "statements for 2 vs 20 duplicate items");
    }

    // ------------------------------------------------------------------ import

    private static ParsedRow trade(int idx, String symbol, String isin, String qty, String price, String date) {
        return new ParsedRow(idx, "trade", InvestmentTransactionType.buy, symbol, isin, "Name " + symbol, "NSE",
                new BigDecimal(qty), new BigDecimal(price), null, LocalDate.parse(date), null, null, Map.of(), null);
    }

    private static ParsedRow dividendRow(int idx, String isin, String amount, String date) {
        return new ParsedRow(idx, "dividend", null, null, isin, "Div", "NSE", null, null, new BigDecimal(amount),
                LocalDate.parse(date), null, null, Map.of(), null);
    }

    private ImportPreviewResponse preview(List<ParsedRow> rows) {
        when(growwParser.parse(any(), any())).thenReturn(rows);
        asUser();
        return importService.preview(new ByteArrayInputStream(new byte[0]), ImportSource.groww, brokerId);
    }

    private static ImportCommitRequest.ParsedRowData tradeData(String qty, String price, String date) {
        return new ImportCommitRequest.ParsedRowData("trade", InvestmentTransactionType.buy, new BigDecimal(qty),
                new BigDecimal(price), null, LocalDate.parse(date), null, null, null);
    }

    private static ImportCommitRequest.ParsedRowData dividendData(String amount, String date) {
        return new ImportCommitRequest.ParsedRowData("dividend", null, null, null, new BigDecimal(amount),
                LocalDate.parse(date), null, null, null);
    }

    private static ImportCommitRequest.CommitRowDto pick(int idx, UUID instrumentId, ImportCommitRequest.ParsedRowData d) {
        return new ImportCommitRequest.CommitRowDto(idx, false, instrumentId, null, d);
    }

    private static ImportCommitRequest.CommitRowDto create(int idx, String symbol, String isin, ImportCommitRequest.ParsedRowData d) {
        return new ImportCommitRequest.CommitRowDto(idx, false, null,
                new ImportCommitRequest.CreateInstrumentDto(null, "Name " + symbol, symbol, "NSE", isin, null, null), d);
    }

    @Test
    void previewResolvesRepeatedIsinsAliasesAndDuplicatesAndReusesInstrumentsItCreates() throws Exception {
        Instrument x = instrument();
        Instrument y = instrument();
        buy(x, "10", "100", "2026-01-05");
        dividend(holdingOf(x), "50", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"));
        String oldSymbol = "OLD" + uniq();
        InstrumentAlias alias = new InstrumentAlias(y, oldSymbol, "Old Y", "USER_EDIT");
        alias.setUserId(userId);
        aliasRepository.save(alias);
        String newIsin = "INE" + uniq() + "7";
        String newSymbol = "NEW" + uniq();

        ImportPreviewResponse res = preview(List.of(
                trade(1, x.getSymbol(), x.getIsin(), "10", "100", "2026-01-05"),   // duplicate of the existing trade
                trade(2, x.getSymbol(), x.getIsin(), "5", "110", "2026-02-01"),    // new
                trade(3, x.getSymbol(), x.getIsin(), "5", "110", "2026-02-01"),    // same ISIN again, not committed yet: new
                trade(4, oldSymbol, null, "3", "50", "2026-02-02"),                // own alias -> Y
                trade(5, oldSymbol, null, "4", "60", "2026-02-03"),                // alias again
                dividendRow(6, x.getIsin(), "50", "2026-03-01"),                   // duplicate dividend
                dividendRow(7, x.getIsin(), "70", "2026-03-01"),                   // new dividend
                new ParsedRow(8, "trade", InvestmentTransactionType.buy, null, null, null, null, null, null, null, null,
                        null, null, Map.of(), "bad row"),                         // parse error
                trade(9, newSymbol, newIsin, "1", "10", "2026-02-04"),             // unknown: instrument created
                trade(10, newSymbol, newIsin, "2", "11", "2026-02-05")));          // same ISIN: the created one reused

        List<ImportPreviewResponse.ImportRowPreviewDto> rows = res.rows();
        assertEquals(10, rows.size());
        assertEquals(List.of(true, false, false, false, false, true, false, false, false, false),
                rows.stream().map(ImportPreviewResponse.ImportRowPreviewDto::duplicate).toList());
        for (int i : new int[]{0, 1, 2, 5, 6}) {
            assertEquals(x.getId(), rows.get(i).matchedInstrument().id());
        }
        assertEquals(y.getId(), rows.get(3).matchedInstrument().id());
        assertEquals(y.getId(), rows.get(4).matchedInstrument().id());
        assertEquals("unmatched", rows.get(7).matchStatus());
        UUID created = rows.get(8).matchedInstrument().id();
        assertEquals(created, rows.get(9).matchedInstrument().id());
        assertEquals(newIsin, instrumentRepository.findByIsin(newIsin).orElseThrow().getIsin());
        assertEquals(9, res.summary().matched());
        assertEquals(2, res.summary().duplicates());
        assertEquals(1, res.summary().errors());
    }

    @Test
    void commitReusesHoldingsInstrumentsAndSavedRowsOfEarlierRowsOfTheSameImport() throws Exception {
        Instrument x = instrument();
        Instrument y = instrument();
        buy(x, "10", "100", "2026-01-05");
        dividend(holdingOf(x), "50", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"));
        String oldSymbol = "OLD" + uniq();
        InstrumentAlias alias = new InstrumentAlias(y, oldSymbol, "Old Y", "USER_EDIT");
        alias.setUserId(userId);
        aliasRepository.save(alias);
        String newIsin = "INE" + uniq() + "8";
        String newSymbol = "NEW" + uniq();
        UUID missing = UUID.randomUUID();

        List<ImportCommitRequest.CommitRowDto> rows = new ArrayList<>(List.of(
                pick(1, x.getId(), tradeData("10", "100", "2026-01-05")),          // duplicate of the existing trade
                pick(2, x.getId(), tradeData("5", "110", "2026-02-01")),           // committed
                pick(3, x.getId(), tradeData("5", "110", "2026-02-01")),           // duplicate of row 2 (same import)
                create(4, oldSymbol, null, tradeData("3", "50", "2026-02-02")),    // own alias -> Y, holding created
                pick(5, y.getId(), tradeData("4", "60", "2026-02-03")),            // reuses the holding made by row 4
                pick(6, x.getId(), dividendData("50", "2026-03-01")),              // duplicate dividend
                pick(7, x.getId(), dividendData("70", "2026-03-01")),              // committed
                pick(8, x.getId(), dividendData("70", "2026-03-01")),              // duplicate of row 7 (same import)
                create(9, newSymbol, newIsin, tradeData("1", "10", "2026-02-04")), // instrument + holding created
                create(10, newSymbol, newIsin, tradeData("2", "11", "2026-02-05")), // both reused
                new ImportCommitRequest.CommitRowDto(11, true, x.getId(), null, tradeData("1", "1", "2026-02-06")),
                pick(12, missing, tradeData("1", "1", "2026-02-07"))));            // unknown instrument id

        ImportCommitResponse res = commit(rows);

        assertEquals(6, res.committed());
        assertEquals(5, res.skipped());
        assertEquals(1, res.failed().size());
        assertEquals(12, res.failed().get(0).rowIndex());
        assertTrue(res.failed().get(0).reason().contains(missing.toString()));
        Map<String, Long> reasons = res.skippedItems().stream().collect(
                java.util.stream.Collectors.groupingBy(i -> i.reason(), java.util.stream.Collectors.counting()));
        assertEquals(Map.of("Duplicate — already in your portfolio", 2L, "Duplicate — dividend already in portfolio", 2L,
                "Excluded during review", 1L), reasons);

        Holding hx = holdingOf(x);
        assertEquals(2, transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(hx.getId()).size());
        assertEquals(2, dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(hx.getId()).size());
        assertEquals(1, holdingRepository.findByBrokerAccountIdAndInstrumentIdIn(brokerId, List.of(y.getId())).size());
        assertEquals(2, transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holdingOf(y).getId()).size());
        Instrument created = instrumentRepository.findByIsin(newIsin).orElseThrow();
        assertEquals(1, instrumentRepository.findByIsinIn(List.of(newIsin)).size());
        assertEquals(2, transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holdingOf(created).getId()).size());
    }

    @Test
    void previewAndCommitQueryCountsDoNotGrowWithRows() throws Exception {
        Instrument x = instrument();
        buy(x, "10", "100", "2026-01-05");
        dividend(holdingOf(x), "50", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01"));

        List<ParsedRow> few = new ArrayList<>();
        List<ParsedRow> many = new ArrayList<>();
        List<ImportCommitRequest.CommitRowDto> fewC = new ArrayList<>();
        List<ImportCommitRequest.CommitRowDto> manyC = new ArrayList<>();
        for (int n = 0; n < 30; n++) {
            // Every row duplicates the existing trade or dividend, so nothing is inserted whatever the row count.
            ParsedRow p = n % 2 == 0 ? trade(n, x.getSymbol(), x.getIsin(), "10", "100", "2026-01-05")
                    : dividendRow(n, x.getIsin(), "50", "2026-03-01");
            ImportCommitRequest.CommitRowDto c = pick(n, x.getId(),
                    n % 2 == 0 ? tradeData("10", "100", "2026-01-05") : dividendData("50", "2026-03-01"));
            many.add(p);
            manyC.add(c);
            if (n < 2) {
                few.add(p);
                fewC.add(c);
            }
        }
        long previewFew = queries(() -> preview(few));
        long previewMany = queries(() -> preview(many));
        long commitFew = queries(() -> commit(fewC));
        long commitMany = queries(() -> commit(manyC));

        assertEquals(previewFew, previewMany, "preview statements for 2 vs 30 rows");
        assertEquals(commitFew, commitMany, "commit statements for 2 vs 30 rows");
    }
}
