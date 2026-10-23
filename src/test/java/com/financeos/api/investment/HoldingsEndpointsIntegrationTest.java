package com.financeos.api.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Holdings list, holding page, sell estimate and the tradebook filters, through the real endpoints.
 * Today is fixed at 2026-10-23 (FY 2026 = 1 Apr 2026 - 31 Mar 2027).
 *
 * <pre>
 *   Alpha Co        stock   A: 10 @100 (2025-06-10, long)  B: 10 @120 (2026-09-15, short)   price 150, previous 145
 *   Beta Bond Fund  mf      A: 100 @10 (2026-04-20, debt)                                    price 12
 *   Big Corp        stock   A: 1000 @100 (2024-01-10)                                        price 400
 *   Delta Old       stock   A: 10 @40, merged into Omega Corp (nobody holds it) on 2026-06-01
 *   Epsilon Ltd     stock   A: 10 @50                                                        no price
 *   Zeta Corp       stock   B: 4 @500                                                        own manual price 600
 *   Gamma Ltd       stock   A: 5 @200 (2025-06-12) sold 5 @250 on 2026-10-18 (sold out)      price 250
 *   Iota Ltd        stock   A: bought and sold 10 on 2026-10-20 (@100 / @105, intraday)       price 105
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldingsEndpointsIntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentPriceRepository priceRepository;
    @Autowired private CorporateActionRepository corporateActionRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;

    private ApiTestClient api;
    private User user;
    private UUID brokerA;
    private UUID brokerB;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> instrumentIds = new ArrayList<>();
    private Instrument alpha;
    private Instrument beta;
    private Instrument big;
    private Instrument delta;
    private Instrument epsilon;
    private Instrument zeta;
    private Instrument gamma;
    private Instrument iota;

    @BeforeEach
    void setUp() throws Exception {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        String email = "holdings-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userIds.add(user.getId());
        brokerA = broker(api, "Broker A", "Zerodha");
        brokerB = broker(api, "Broker B", "Groww");

        alpha = instrument(InstrumentType.stock, "Alpha Co");
        beta = instrument(InstrumentType.mutual_fund, "Beta Bond Fund");
        Instrument omega = instrument(InstrumentType.stock, "Omega Corp");
        big = instrument(InstrumentType.stock, "Big Corp");
        delta = instrument(InstrumentType.stock, "Delta Old");
        epsilon = instrument(InstrumentType.stock, "Epsilon Ltd");
        zeta = instrument(InstrumentType.stock, "Zeta Corp");
        gamma = instrument(InstrumentType.stock, "Gamma Ltd");
        iota = instrument(InstrumentType.stock, "Iota Ltd");

        price(alpha, TODAY, "150");
        price(alpha, TODAY.minusDays(1), "145");
        price(beta, TODAY, "12");
        price(big, TODAY, "400");
        price(delta, TODAY.minusDays(200), "40");
        price(gamma, TODAY, "250");
        price(iota, TODAY, "105");
        priceRepository.save(InstrumentPrice.manual(zeta, user.getId(), TODAY, new BigDecimal("600")));

        trade(brokerA, alpha, "buy", "10", "100", "2025-06-10");
        trade(brokerB, alpha, "buy", "10", "120", "2026-09-15");
        trade(brokerA, beta, "buy", "100", "10", "2026-04-20");
        trade(brokerA, big, "buy", "1000", "100", "2024-01-10");
        trade(brokerA, delta, "buy", "10", "40", "2026-01-05");
        trade(brokerA, epsilon, "buy", "10", "50", "2026-08-01");
        trade(brokerB, zeta, "buy", "4", "500", "2026-07-01");
        trade(brokerA, gamma, "buy", "5", "200", "2025-06-12");
        trade(brokerA, gamma, "sell", "5", "250", "2026-10-18");
        intraday(brokerA, iota, "buy", "10", "100", "2026-10-20");
        intraday(brokerA, iota, "sell", "10", "105", "2026-10-20");

        CorporateAction merger = new CorporateAction();
        merger.setUser(user);
        merger.setInstrument(delta);
        merger.setType(CorporateActionType.merger);
        merger.setTargetInstrument(omega);
        merger.setRatioFrom(1);
        merger.setRatioTo(1);
        merger.setExDate(LocalDate.of(2026, 6, 1));
        corporateActionRepository.save(merger);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, userIds);
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID broker(ApiTestClient client, String name, String provider) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", provider,
                "clientId", "CL" + UUID.randomUUID().toString().substring(0, 5), "cashBalance", 5000,
                "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private Instrument instrument(InstrumentType type, String name) {
        String symbol = name.substring(0, 3).toUpperCase() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Instrument i = new Instrument();
        i.setType(type);
        i.setName(name);
        i.setSymbol(symbol);
        i.setExchange("NSE");
        i.setIsin("INE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase() + "1");
        i.setYahooSymbol(symbol + ".NS");
        Instrument saved = instrumentRepository.save(i);
        instrumentIds.add(saved.getId());
        return saved;
    }

    private void price(Instrument i, LocalDate asOf, String close) {
        priceRepository.save(new InstrumentPrice(i, asOf, new BigDecimal(close), PriceSource.YAHOO));
    }

    private void trade(UUID broker, Instrument i, String type, String qty, String price, String date) throws Exception {
        trade(api, broker, i, type, qty, price, date);
    }

    private void intraday(UUID broker, Instrument i, String type, String qty, String price, String date) throws Exception {
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", i.getId().toString(), "type", type, "quantity", qty, "price", price, "tradeDate", date,
                "settlementType", "intraday"));
    }

    private void trade(ApiTestClient client, UUID broker, Instrument i, String type, String qty, String price, String date)
            throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", i.getId().toString(), "type", type, "quantity", qty, "price", price, "tradeDate", date));
    }

    private JsonNode holdings(String query) throws Exception {
        return api.getJson("/api/v1/investments/holdings" + query, 200);
    }

    private static List<String> names(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.get("holdings").forEach(r -> out.add(r.get("name").asText()));
        return out;
    }

    private static JsonNode row(JsonNode response, String name) {
        for (JsonNode r : response.get("holdings")) {
            if (name.equals(r.get("name").asText())) {
                return r;
            }
        }
        throw new AssertionError("no row " + name + " in " + names(response));
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertFalse(actual == null || actual.isNull(), "expected " + expected + " but was null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    // ------------------------------------------------------------------ holdings list

    @Test
    void oneRowPerInstrumentAcrossBrokersWithSoldOutHidden() throws Exception {
        JsonNode r = holdings("");
        assertEquals(List.of("Alpha Co", "Beta Bond Fund", "Big Corp", "Delta Old", "Epsilon Ltd", "Zeta Corp"), names(r));

        JsonNode a = row(r, "Alpha Co");
        assertEquals(alpha.getId().toString(), a.get("instrumentId").asText());
        assertEquals("stock", a.get("assetType").asText());
        assertMoney("20", a.get("quantity"));
        assertMoney("110", a.get("avgCost"));
        assertMoney("150", a.get("ltp"));
        assertEquals("2026-10-23", a.get("priceAsOf").asText());
        assertFalse(a.get("manualPrice").asBoolean());
        assertFalse(a.get("noPrice").asBoolean());
        assertMoney("3000", a.get("currentValue"));
        assertMoney("2200", a.get("invested"));
        assertMoney("800", a.get("unrealised"));
        assertMoney("36.36", a.get("unrealisedPct"));
        assertMoney("100", a.get("dayChange"));
        assertMoney("3.45", a.get("dayChangePct"));
        assertEquals(2, a.get("brokerCount").asInt());
        assertTrue(a.get("mergedIntoName").isNull());
    }

    @Test
    void rowsWithoutAPreviousCloseHaveNoDayChange() throws Exception {
        JsonNode b = row(holdings(""), "Beta Bond Fund");
        assertTrue(b.get("dayChange").isNull());
        assertTrue(b.get("dayChangePct").isNull());
        assertEquals(1, b.get("brokerCount").asInt());
    }

    @Test
    void manualAndMissingPricesAreFlagged() throws Exception {
        JsonNode r = holdings("");
        JsonNode z = row(r, "Zeta Corp");
        assertTrue(z.get("manualPrice").asBoolean());
        assertMoney("600", z.get("ltp"));
        assertMoney("2400", z.get("currentValue"));

        JsonNode e = row(r, "Epsilon Ltd");
        assertTrue(e.get("noPrice").asBoolean());
        assertTrue(e.get("ltp").isNull());
        assertMoney("500", e.get("currentValue"));
        assertMoney("0", e.get("unrealised"));
    }

    @Test
    void aFullyMergedOutHoldingShowsItsMergedIntoRowAndOtherSoldOutOnesStayHidden() throws Exception {
        JsonNode r = holdings("");
        JsonNode d = row(r, "Delta Old");
        assertMoney("0", d.get("quantity"));
        assertEquals("Omega Corp", d.get("mergedIntoName").asText());
        assertEquals("2026-06-01", d.get("mergedIntoDate").asText());
        assertFalse(names(r).contains("Gamma Ltd"), "sold out, not merged");
        assertFalse(names(r).contains("Iota Ltd"), "intraday only, nothing held");
    }

    @Test
    void headerAndGroupsCoverTheWholeFilteredSet() throws Exception {
        JsonNode r = holdings("");
        JsonNode h = r.get("header");
        assertMoney("407100", h.get("currentValue"));
        assertMoney("105700", h.get("invested"));
        assertMoney("301400", h.get("unrealised"));
        assertMoney("285.15", h.get("unrealisedPct"));
        assertMoney("100", h.get("dayChange"));
        assertMoney("3.45", h.get("dayChangePct"));
        assertEquals("2026-10-23", h.get("priceAsOf").asText());
        assertNotNull(h.get("xirr"));

        JsonNode groups = r.get("groups");
        assertEquals(2, groups.size());
        for (JsonNode g : groups) {
            if ("stock".equals(g.get("assetType").asText())) {
                assertMoney("405900", g.get("value"));
                assertEquals(4, g.get("count").asInt());
            } else {
                assertEquals("mutual_fund", g.get("assetType").asText());
                assertMoney("1200", g.get("value"));
            }
        }
    }

    @Test
    void brokerFilterKeepsOnlyThatBrokersHoldingsAndTotals() throws Exception {
        JsonNode r = holdings("?brokerAccountId=" + brokerB);
        assertEquals(List.of("Alpha Co", "Zeta Corp"), names(r));
        JsonNode a = row(r, "Alpha Co");
        assertMoney("10", a.get("quantity"));
        assertMoney("120", a.get("avgCost"));
        assertMoney("1500", a.get("currentValue"));
        assertEquals(1, a.get("brokerCount").asInt());
        assertMoney("3900", r.get("header").get("currentValue"));
        assertFalse(names(r).contains("Delta Old"), "merged out of broker A's holding only");
    }

    @Test
    void assetTypeFilter() throws Exception {
        JsonNode mf = holdings("?assetType=mutual_fund");
        assertEquals(List.of("Beta Bond Fund"), names(mf));
        assertMoney("1200", mf.get("header").get("currentValue"));
        assertEquals(1, mf.get("groups").size());
        assertEquals(5, holdings("?assetType=stock").get("totalElements").asInt());
        assertEquals(0, holdings("?assetType=etf").get("totalElements").asInt());
        api.getJson("/api/v1/investments/holdings?assetType=bond", 400);
    }

    @Test
    void searchMatchesNameOrSymbolIgnoringCase() throws Exception {
        assertEquals(List.of("Alpha Co"), names(holdings("?search=ALPHA")));
        assertEquals(List.of("Epsilon Ltd"), names(holdings("?search=" + epsilon.getSymbol().toLowerCase())));
        assertEquals(List.of(), names(holdings("?search=nothing-like-this")));
    }

    @Test
    void sortByReturnPercentIsBestFirstWithNoReturnLast() throws Exception {
        assertEquals(List.of("Big Corp", "Alpha Co", "Beta Bond Fund", "Zeta Corp", "Epsilon Ltd", "Delta Old"),
                names(holdings("?sort=return_pct")));
    }

    @Test
    void sortByReturnAmountIsBestFirst() throws Exception {
        assertEquals(List.of("Big Corp", "Alpha Co", "Zeta Corp", "Beta Bond Fund", "Delta Old", "Epsilon Ltd"),
                names(holdings("?sort=return_abs")));
    }

    @Test
    void sortByNameIsTheDefaultAndAnUnknownSortIsRejected() throws Exception {
        assertEquals(names(holdings("")), names(holdings("?sort=name")));
        api.getJson("/api/v1/investments/holdings?sort=weird", 400);
    }

    @Test
    void paginationTotalsDescribeTheFilteredSetNotThePage() throws Exception {
        JsonNode p1 = holdings("?size=2&page=1");
        assertEquals(List.of("Big Corp", "Delta Old"), names(p1));
        assertEquals(6, p1.get("totalElements").asInt());
        assertEquals(3, p1.get("totalPages").asInt());
        assertEquals(1, p1.get("page").asInt());
        assertEquals(2, p1.get("size").asInt());
        assertMoney("407100", p1.get("header").get("currentValue"));
        assertEquals(2, p1.get("groups").size());
        assertEquals(List.of(), names(holdings("?size=2&page=5")));
        assertEquals(50, holdings("").get("size").asInt());
    }

    @Test
    void anotherUsersHoldingsNeverAppear() throws Exception {
        String email = "holdings-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, email);
        userIds.add(userRepository.findByEmail(email).orElseThrow().getId());
        UUID otherBroker = broker(other, "Other Broker", "Zerodha");
        trade(other, otherBroker, alpha, "buy", "7", "90", "2026-01-01");

        assertMoney("20", row(holdings(""), "Alpha Co").get("quantity"));
        JsonNode theirs = other.getJson("/api/v1/investments/holdings", 200);
        assertEquals(List.of("Alpha Co"), names(theirs));
        assertMoney("7", row(theirs, "Alpha Co").get("quantity"));
        other.getJson("/api/v1/investments/holdings/" + gamma.getId(), 404);
    }

    // ------------------------------------------------------------------ holding page

    @Test
    void holdingPageSumsBrokersAndCarriesRealisedFirstBuyAndTerm() throws Exception {
        JsonNode h = api.getJson("/api/v1/investments/holdings/" + alpha.getId(), 200);
        assertMoney("20", h.get("quantity"));
        assertMoney("110", h.get("avgCost"));
        assertMoney("3000", h.get("currentValue"));
        assertMoney("2200", h.get("invested"));
        assertMoney("800", h.get("unrealised"));
        assertMoney("100", h.get("dayChange"));
        assertMoney("0", h.get("realisedPnl"));
        assertMoney("0", h.get("totalCharges"));
        assertEquals("2025-06-10", h.get("firstBuyDate").asText());
        assertEquals("mixed", h.get("term").asText(), "one long and one short lot");
        assertNotNull(h.get("xirr"));
        JsonNode brokers = h.get("brokers");
        assertEquals(2, brokers.size());
        for (JsonNode b : brokers) {
            if (brokerA.toString().equals(b.get("brokerAccountId").asText())) {
                assertEquals("Broker A", b.get("accountName").asText());
                assertMoney("10", b.get("quantity"));
                assertMoney("100", b.get("avgCost"));
                assertMoney("1500", b.get("currentValue"));
            } else {
                assertEquals(brokerB.toString(), b.get("brokerAccountId").asText());
                assertMoney("120", b.get("avgCost"));
            }
        }
    }

    @Test
    void holdingPageOfASoldOutInstrumentStillReportsRealisedPnl() throws Exception {
        JsonNode h = api.getJson("/api/v1/investments/holdings/" + gamma.getId(), 200);
        assertMoney("0", h.get("quantity"));
        assertMoney("250", h.get("realisedPnl"));
        assertEquals(0, h.get("brokers").size());
        assertTrue(h.get("term").isNull());
    }

    @Test
    void holdingPageTermIsLongWhenEveryOpenLotIsLong() throws Exception {
        assertEquals("long", api.getJson("/api/v1/investments/holdings/" + big.getId(), 200).get("term").asText());
        assertEquals("slab", api.getJson("/api/v1/investments/holdings/" + beta.getId(), 200).get("term").asText());
    }

    @Test
    void holdingPageOfAMergedOutInstrumentCarriesMergedInto() throws Exception {
        JsonNode h = api.getJson("/api/v1/investments/holdings/" + delta.getId(), 200);
        assertEquals("Omega Corp", h.get("mergedIntoName").asText());
    }

    @Test
    void holdingPageIs404ForAnInstrumentTheUserNeverHeld() throws Exception {
        Instrument stranger = instrument(InstrumentType.stock, "Stranger Ltd");
        api.getJson("/api/v1/investments/holdings/" + stranger.getId(), 404);
        api.getJson("/api/v1/investments/holdings/" + UUID.randomUUID(), 404);
    }

    // ------------------------------------------------------------------ sell estimate

    @Test
    void sellEstimateTakesTheOldestLotsFirstAndSplitsLongFromShort() throws Exception {
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate?quantity=15", 200);
        assertMoney("15", e.get("quantity"));
        assertMoney("2250", e.get("proceeds"));
        assertMoney("1600", e.get("cost"));
        assertMoney("650", e.get("gain"));
        assertMoney("500", e.get("longTermGain"));
        assertMoney("150", e.get("shortTermGain"));
        assertTrue(e.get("equity").asBoolean());
        assertEquals(2, e.get("lots").size());
        assertEquals("2025-06-10", e.get("lots").get(0).get("buyDate").asText());
        assertEquals("long", e.get("lots").get(0).get("term").asText());
        assertEquals("short", e.get("lots").get(1).get("term").asText());
        assertMoney("5", e.get("lots").get(1).get("quantity"));
        assertMoney("0.125", e.get("ltcgRate"));
        assertMoney("0.2", e.get("stcgRate"));
        assertMoney("30", e.get("stcgTax"));
        assertMoney("0", e.get("ltcgTax"));
        assertMoney("30", e.get("tax"));
        // Gamma's FY-2026 long-term gain of 250 already used some of the exemption.
        assertMoney("124750", e.get("exemptionLeftBefore"));
        assertMoney("124250", e.get("exemptionLeftAfter"));
    }

    @Test
    void sellEstimateChargesLtcgOnlyAboveTheExemptionLeft() throws Exception {
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + big.getId() + "/sell-estimate", 200);
        assertMoney("1000", e.get("quantity"));
        assertMoney("300000", e.get("longTermGain"));
        assertMoney("0", e.get("shortTermGain"));
        assertMoney("0", e.get("stcgTax"));
        // 300,000 + 250 booked - 125,000 exemption = 175,250 taxable, of which 0 was taxable before.
        assertMoney("21906.25", e.get("ltcgTax"));
        assertMoney("21906.25", e.get("tax"));
        assertMoney("124750", e.get("exemptionLeftBefore"));
        assertMoney("0", e.get("exemptionLeftAfter"));
    }

    /** Books a short-term loss of 300 and a long-term loss of 400 in FY 2026 (on top of Gamma's 250 long-term gain). */
    private void bookLosses() throws Exception {
        Instrument shortLoss = instrument(InstrumentType.stock, "Short Loss Co");
        Instrument longLoss = instrument(InstrumentType.stock, "Long Loss Co");
        trade(brokerA, shortLoss, "buy", "10", "100", "2026-08-01");
        trade(brokerA, shortLoss, "sell", "10", "70", "2026-09-10");
        trade(brokerA, longLoss, "buy", "10", "100", "2025-06-01");
        trade(brokerA, longLoss, "sell", "10", "60", "2026-09-01");
    }

    @Test
    void sellEstimateSetsTheYearsBookedLossesOffTheSalesGain() throws Exception {
        bookLosses();
        // Big's 300,000 long-term gain: net long term is 300,250 - 400 = 299,850, the 300 short-term loss takes
        // that to 299,550, less the 125,000 exemption = 174,550 taxable (21,818.75), against 21,906.25 with no losses.
        JsonNode big = api.getJson("/api/v1/investments/holdings/" + this.big.getId() + "/sell-estimate", 200);
        assertMoney("300000", big.get("gain"));
        assertMoney("0", big.get("stcgTax"));
        assertMoney("21818.75", big.get("ltcgTax"));
        assertMoney("21818.75", big.get("tax"));

        // Alpha 15: the 150 short-term gain is absorbed by the 300 short-term loss and the long-term gain by the
        // long-term loss and exemption, so the tax falls from 30 to 0 and never goes below it.
        JsonNode alphaEstimate = api.getJson("/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate?quantity=15", 200);
        assertMoney("650", alphaEstimate.get("gain"));
        assertMoney("0", alphaEstimate.get("stcgTax"));
        assertMoney("0", alphaEstimate.get("ltcgTax"));
        assertMoney("0", alphaEstimate.get("tax"));
    }

    @Test
    void sellEstimateForANonEquityHoldingHasTheGainButNoTax() throws Exception {
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + beta.getId() + "/sell-estimate?quantity=40", 200);
        assertFalse(e.get("equity").asBoolean());
        assertMoney("80", e.get("gain"));
        assertMoney("0", e.get("longTermGain"));
        assertMoney("80", e.get("shortTermGain"));
        assertTrue(e.get("tax").isNull());
        assertTrue(e.get("stcgTax").isNull());
        assertTrue(e.get("ltcgTax").isNull());
        assertTrue(e.get("exemptionLeftAfter").isNull());
    }

    @Test
    void sellEstimateReturnsTheYearsBookedPoolsAndTheExemptionLimit() throws Exception {
        // Only Gamma's 250 long-term gain is booked this FY: no short-term P&L at all.
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate?quantity=15", 200);
        assertMoney("0", e.get("bookedShortTermGain"));
        assertMoney("250", e.get("bookedEquityLongTermGain"));
        assertMoney("0", e.get("bookedOtherLongTermGain"));
        assertMoney("125000", e.get("exemptionLimit"));
    }

    @Test
    void sellEstimateBookedPoolsIncludeTheYearsLosses() throws Exception {
        bookLosses();
        // 300 short-term loss; long term is Gamma's 250 gain less the 400 loss.
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate?quantity=15", 200);
        assertMoney("-300", e.get("bookedShortTermGain"));
        assertMoney("-150", e.get("bookedEquityLongTermGain"));
        assertMoney("0", e.get("bookedOtherLongTermGain"));
        assertMoney("125000", e.get("exemptionLimit"));
    }

    @Test
    void sellEstimateBookedPoolsSplitNonEquityLongTermFromEquity() throws Exception {
        // A non-equity long-term gain of 50 booked this FY goes to the other pool, not the equity one.
        trade(brokerA, beta, "buy", "10", "10", "2022-01-01");
        trade(brokerA, beta, "sell", "10", "15", "2026-09-15");
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate?quantity=15", 200);
        assertMoney("0", e.get("bookedShortTermGain"));
        assertMoney("250", e.get("bookedEquityLongTermGain"));
        assertMoney("50", e.get("bookedOtherLongTermGain"));
    }

    @Test
    void sellEstimateForANonEquityHoldingHasNoBookedPools() throws Exception {
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + beta.getId() + "/sell-estimate?quantity=40", 200);
        assertTrue(e.get("bookedShortTermGain").isNull());
        assertTrue(e.get("bookedEquityLongTermGain").isNull());
        assertTrue(e.get("bookedOtherLongTermGain").isNull());
        assertTrue(e.get("exemptionLimit").isNull());
    }

    @Test
    void sellEstimateWithoutAPriceIsAtCost() throws Exception {
        JsonNode e = api.getJson("/api/v1/investments/holdings/" + epsilon.getId() + "/sell-estimate", 200);
        assertTrue(e.get("noPrice").asBoolean());
        assertTrue(e.get("price").isNull());
        assertMoney("0", e.get("gain"));
    }

    @Test
    void sellEstimateRejectsTooMuchOrNothingAndMissingInstruments() throws Exception {
        String base = "/api/v1/investments/holdings/" + alpha.getId() + "/sell-estimate";
        api.getJson(base + "?quantity=21", 400);
        api.getJson(base + "?quantity=0", 400);
        api.getJson("/api/v1/investments/holdings/" + gamma.getId() + "/sell-estimate", 400);
        api.getJson("/api/v1/investments/holdings/" + UUID.randomUUID() + "/sell-estimate", 404);
    }

    // ------------------------------------------------------------------ tradebook

    private JsonNode txns(String query) throws Exception {
        return api.getJson("/api/v1/investments/transactions?size=100" + query, 200).get("content");
    }

    private static JsonNode txnOf(JsonNode content, String instrumentName, String type) {
        for (JsonNode t : content) {
            if (instrumentName.equals(t.get("instrument").get("name").asText()) && type.equals(t.get("type").asText())) {
                return t;
            }
        }
        throw new AssertionError("no " + type + " of " + instrumentName);
    }

    @Test
    void typeFilterKeepsBuysOrSells() throws Exception {
        JsonNode sells = txns("&type=sell");
        assertEquals(2, sells.size());
        sells.forEach(t -> assertEquals("sell", t.get("type").asText()));
        JsonNode buys = txns("&type=buy");
        assertEquals(9, buys.size());
        buys.forEach(t -> assertEquals("buy", t.get("type").asText()));
        assertEquals(11, txns("").size());
    }

    @Test
    void fyFilterIsAprilToMarchByStartYear() throws Exception {
        // FY 2025 (Apr 2025 - Mar 2026): Alpha A, Gamma buy, Delta buy.
        assertEquals(3, txns("&fy=2025").size());
        // FY 2026: everything dated Apr 2026 on.
        JsonNode fy26 = txns("&fy=2026");
        assertEquals(7, fy26.size());
        fy26.forEach(t -> assertTrue(t.get("tradeDate").asText().compareTo("2026-04-01") >= 0));
        assertEquals(2, txns("&fy=2026&type=sell").size());
        assertEquals(0, txns("&fy=2025&type=sell").size());
        assertEquals(1, txns("&fy=2023").size(), "Big Corp, bought 2024-01-10, is FY 2023");
        api.getJson("/api/v1/investments/transactions?fy=12", 400);
    }

    @Test
    void sellRowsCarryRealisedPnlAndBuysDoNot() throws Exception {
        JsonNode all = txns("");
        assertMoney("250", txnOf(all, "Gamma Ltd", "sell").get("realisedPnl"));
        assertTrue(txnOf(all, "Gamma Ltd", "buy").get("realisedPnl").isNull());
        assertTrue(txnOf(all, "Alpha Co", "buy").get("realisedPnl").isNull());
    }

    @Test
    void settlementIsDeliveryUnlessTheDayWasNettedIntraday() throws Exception {
        JsonNode all = txns("");
        assertEquals("delivery", txnOf(all, "Gamma Ltd", "sell").get("settlement").asText());
        assertEquals("delivery", txnOf(all, "Alpha Co", "buy").get("settlement").asText());
        JsonNode intradaySell = txnOf(all, "Iota Ltd", "sell");
        assertEquals("intraday", intradaySell.get("settlement").asText());
        assertEquals("intraday", txnOf(all, "Iota Ltd", "buy").get("settlement").asText());
        // The netted day's realised P&L (1050 - 1000) goes to its sell.
        assertMoney("50", intradaySell.get("realisedPnl"));
    }

    @Test
    void anIntradayNettedDaysPnlIsSharedAcrossItsSellsByQuantity() throws Exception {
        Instrument kappa = instrument(InstrumentType.stock, "Kappa Ltd");
        intraday(brokerA, kappa, "buy", "10", "100", "2026-10-21");
        intraday(brokerA, kappa, "sell", "6", "110", "2026-10-21");
        intraday(brokerA, kappa, "sell", "4", "105", "2026-10-21");
        // day P&L: 660 + 420 - 1000 = 80, shared 6/10 and 4/10
        JsonNode sells = api.getJson("/api/v1/investments/transactions?size=100&instrumentId=" + kappa.getId() + "&type=sell", 200)
                .get("content");
        assertEquals(2, sells.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode t : sells) {
            assertEquals("intraday", t.get("settlement").asText());
            assertMoney(t.get("quantity").decimalValue().compareTo(new BigDecimal("6")) == 0 ? "48" : "32", t.get("realisedPnl"));
            sum = sum.add(t.get("realisedPnl").decimalValue());
        }
        assertEquals(0, new BigDecimal("80").compareTo(sum));
    }

    @Test
    void aPartiallyNettedDayCallsTradesUpToTheNettedQuantityIntraday() throws Exception {
        Instrument lambda = instrument(InstrumentType.stock, "Lambda Ltd");
        trade(brokerA, lambda, "buy", "10", "100", "2026-10-01");
        intraday(brokerA, lambda, "buy", "4", "100", "2026-10-22");
        trade(brokerA, lambda, "buy", "10", "100", "2026-10-22");
        intraday(brokerA, lambda, "sell", "4", "110", "2026-10-22");
        trade(brokerA, lambda, "sell", "3", "120", "2026-10-22");
        JsonNode day = api.getJson("/api/v1/investments/transactions?size=100&instrumentId=" + lambda.getId()
                + "&fy=2026", 200).get("content");
        // netted quantity 4: the untagged 10-unit buy is bigger than that so it stays delivery; the 4 sell is the netted
        // one; the delivery-tagged 3 sell is within the netted quantity so the heuristic also calls it intraday
        for (JsonNode t : day) {
            if (!"2026-10-22".equals(t.get("tradeDate").asText())) {
                continue;
            }
            boolean buy = "buy".equals(t.get("type").asText());
            boolean three = !buy && t.get("quantity").decimalValue().compareTo(new BigDecimal("3")) == 0;
            boolean bigBuy = buy && t.get("quantity").decimalValue().compareTo(new BigDecimal("10")) == 0;
            assertEquals(bigBuy ? "delivery" : "intraday", t.get("settlement").asText(), (buy ? "buy" : three ? "sell 3" : "sell 4"));
        }
    }

    @Test
    void plainListingStillWorksWithoutTheNewFilters() throws Exception {
        JsonNode page = api.getJson("/api/v1/investments/transactions?instrumentId=" + gamma.getId(), 200);
        assertEquals(2, page.get("totalElements").asInt());
    }

    // ------------------------------------------------------------------ no per-row queries

    private long queriesFor(String path) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        jobWorker.pause();
        try {
            stats.clear();
            api.getJson(path, 200);
            return stats.getPrepareStatementCount();
        } finally {
            jobWorker.resume();
        }
    }

    @Test
    void queryCountDoesNotGrowWithTheNumberOfHoldings() throws Exception {
        String list = "/api/v1/investments/holdings";
        String sells = "/api/v1/investments/transactions?type=sell&size=100";
        queriesFor(list);
        queriesFor(sells);
        long listBefore = queriesFor(list);
        long sellsBefore = queriesFor(sells);

        for (int i = 0; i < 4; i++) {
            Instrument more = instrument(InstrumentType.stock, "More " + i);
            price(more, TODAY, "10");
            trade(brokerA, more, "buy", "10", "5", "2026-05-01");
            trade(brokerA, more, "sell", "2", "7", "2026-06-01");
        }
        assertEquals(listBefore, queriesFor(list), "holdings list");
        assertEquals(sellsBefore, queriesFor(sells), "tradebook sells");
    }
}
