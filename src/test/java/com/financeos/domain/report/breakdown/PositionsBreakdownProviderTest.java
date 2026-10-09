package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.HoldingTrace;
import com.financeos.domain.investment.HoldingTrace.Event;
import com.financeos.domain.investment.HoldingTrace.EventKind;
import com.financeos.domain.investment.HoldingTrace.LotSource;
import com.financeos.domain.investment.HoldingTrace.OpenLot;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class PositionsBreakdownProviderTest {

    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 10, 8);

    private InvestmentService investmentService;
    private PositionsBreakdownProvider provider;

    private Holding holding;
    private String rowId;

    @BeforeEach
    void setUp() {
        investmentService = mock(InvestmentService.class);
        provider = new PositionsBreakdownProvider(investmentService);

        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        holding = new Holding(broker, instrument("Tata Motors", InstrumentType.stock), null);
        holding.setId(UUID.randomUUID());
        rowId = holding.getId().toString();
    }

    @Test
    void explainsThePositionsDatasource() {
        assertEquals("positions", provider.datasource());
    }

    // ---- row lookup ----

    @Test
    void rowIdThatIsNotAHoldingIdIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> provider.breakdown("not-a-uuid", 25));
        assertThrows(ResourceNotFoundException.class, () -> provider.section("not-a-uuid", "lots", 0, 25));
        verifyNoInteractions(investmentService);
    }

    @Test
    void unknownSectionIsNotFound() {
        stubTrace(open("10", "1000", "1200", "200", new BigDecimal("120")), List.of(), List.of());

        assertThrows(ResourceNotFoundException.class, () -> provider.section(rowId, "matches", 0, 25));
    }

    // ---- header and steps ----

    @Test
    void openHoldingWithAGainReconcilesCostPlusUnrealisedToCurrentValue() {
        stubTrace(open("10", "1000.5", "1200", "199.5", new BigDecimal("120")), List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertEquals("positions", r.datasource());
        assertEquals(rowId, r.rowId());
        assertEquals("Tata Motors", r.title());
        assertEquals("Zerodha", r.subtitle());
        assertEquals("Stock", r.kindLabel());
        assertEquals(new BigDecimal("1200"), r.total());
        assertEquals("Current value", r.totalLabel());
        assertEquals("currency", r.format());
        assertEquals(AppTime.today(), r.asOf());
        assertEquals(List.of(
                new BreakdownStep("info", "Open quantity", null, new BigDecimal("10"), "number"),
                new BreakdownStep("info", "Average cost", null, new BigDecimal("100.05"), "currency"),
                new BreakdownStep("start", "Cost of open lots", null, new BigDecimal("1000.5"), "currency"),
                new BreakdownStep("add", "Unrealised gain", null, new BigDecimal("199.5"), "currency"),
                new BreakdownStep("equals", "Current value", null, new BigDecimal("1200"), "currency"),
                new BreakdownStep("info", "Latest price", "08/10/2026 · Yahoo Finance", new BigDecimal("120"), "currency"),
                new BreakdownStep("info", "Realised P&L", null, new BigDecimal("50"), "currency"),
                new BreakdownStep("info", "Dividends", null, new BigDecimal("12"), "currency"),
                new BreakdownStep("info", "Charges", null, new BigDecimal("3"), "currency")),
                r.steps());
        assertChainReconciles(r);
    }

    @Test
    void openHoldingWithALossSubtractsTheUnrealisedLoss() {
        stubTrace(open("10", "1000", "900", "-100", new BigDecimal("90")), List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertTrue(r.steps().contains(new BreakdownStep("subtract", "Unrealised loss", null, new BigDecimal("100"), "currency")));
        assertChainReconciles(r);
    }

    @Test
    void openHoldingAtExactlyCostHasNoUnrealisedStep() {
        stubTrace(open("10", "1000", "1000", "0", new BigDecimal("100")), List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertEquals(List.of("info", "info", "start", "equals", "info", "info", "info", "info"),
                r.steps().stream().map(BreakdownStep::op).toList());
        assertChainReconciles(r);
    }

    @Test
    void holdingWithoutAPriceIsValuedAtCost() {
        HoldingPosition p = position("10", "100", "1000", null, null, null, "1000", "0", null, null);
        stubTrace(p, List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertEquals(List.of(
                new BreakdownStep("start", "Cost of open lots", null, new BigDecimal("1000"), "currency"),
                new BreakdownStep("equals", "Current value", null, new BigDecimal("1000"), "currency"),
                new BreakdownStep("info", "No price — valued at cost", null, null, null)),
                r.steps().subList(2, 5));
        assertTrue(r.steps().stream().noneMatch(s -> s.label().equals("Latest price")));
        assertChainReconciles(r);
    }

    @Test
    void closedHoldingIsZeroWithAPositionClosedFact() {
        stubTrace(position("0", "0", "0", new BigDecimal("150"), PRICE_DATE, PriceSource.YAHOO, null, null, null, null),
                List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertEquals(BigDecimal.ZERO, r.total());
        assertEquals(List.of(
                new BreakdownStep("info", "Position closed", null, null, null),
                new BreakdownStep("start", "Cost of open lots", null, new BigDecimal("0"), "currency"),
                new BreakdownStep("equals", "Current value", null, BigDecimal.ZERO, "currency"),
                new BreakdownStep("info", "Realised P&L", null, new BigDecimal("50"), "currency"),
                new BreakdownStep("info", "Dividends", null, new BigDecimal("12"), "currency"),
                new BreakdownStep("info", "Charges", null, new BigDecimal("3"), "currency")),
                r.steps());
        assertChainReconciles(r);
    }

    @Test
    void mergedAwayHoldingSaysWhereItWent() {
        stubTrace(position("0", "0", "0", null, null, null, null, null, "HDFC Bank", LocalDate.of(2023, 7, 13)),
                List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertEquals(new BreakdownStep("info", "Position closed", "Merged into HDFC Bank on 13/07/2023", null, null),
                r.steps().get(0));
    }

    @Test
    void residualBetweenChainAndCurrentValueIsAnExplicitRoundingStep() {
        stubTrace(open("10", "1000", "1200", "199.9999", new BigDecimal("120")), List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertTrue(r.steps().contains(new BreakdownStep("add", "Rounding difference", null, new BigDecimal("0.0001"), "currency")));
        assertChainReconciles(r);
    }

    @Test
    void negativeResidualIsSubtractedAsARoundingStep() {
        stubTrace(open("10", "1000", "1200", "200.0001", new BigDecimal("120")), List.of(), List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 25);

        assertTrue(r.steps().contains(new BreakdownStep("subtract", "Rounding difference", null, new BigDecimal("0.0001"), "currency")));
        assertChainReconciles(r);
    }

    @Test
    void priceDetailShowsOnlyTheKnownPartsAndEachSourceByName() {
        assertEquals("08/10/2026 · AMFI", latestPriceDetail(PRICE_DATE, PriceSource.AMFI));
        assertEquals("08/10/2026 · Manual", latestPriceDetail(PRICE_DATE, PriceSource.MANUAL));
        assertEquals("08/10/2026", latestPriceDetail(PRICE_DATE, null));
        assertEquals("Yahoo Finance", latestPriceDetail(null, PriceSource.YAHOO));
        assertNull(latestPriceDetail(null, null));
    }

    @Test
    void kindLabelNamesTheInstrumentType() {
        assertEquals("Mutual fund", kindLabelFor(InstrumentType.mutual_fund));
        assertEquals("ETF", kindLabelFor(InstrumentType.etf));
        assertNull(kindLabelFor(null));
    }

    @Test
    void notesExplainCostAndIntradayPnlOnlyWhenThereIsSome() {
        stubTrace(open("10", "1000", "1200", "200", new BigDecimal("120")), List.of(), List.of());
        assertEquals(List.of("Cost is the traded price of the lots still open after first-in, first-out matching; charges are not included."),
                provider.breakdown(rowId, 25).notes());

        HoldingPosition withIntraday = withIntradayRealized(open("10", "1000", "1200", "200", new BigDecimal("120")), new BigDecimal("-1250.5"));
        stubTrace(withIntraday, List.of(), List.of());
        assertEquals("Intraday P&L of -₹1,250.50 is not part of Realised P&L.", provider.breakdown(rowId, 25).notes().get(1));
    }

    // ---- sections ----

    @Test
    void breakdownCarriesTheFirstPageOfLotsAndHistory() {
        List<OpenLot> lots = List.of(
                lot(LocalDate.of(2024, 1, 1), LotSource.BUY, "1", "10"),
                lot(LocalDate.of(2024, 2, 1), LotSource.BUY, "1", "10"),
                lot(LocalDate.of(2024, 3, 1), LotSource.BUY, "1", "10"));
        stubTrace(open("3", "30", "30", "0", null), lots, List.of());

        RowBreakdownResponse r = provider.breakdown(rowId, 2);

        assertEquals(List.of("lots", "history"), r.sections().stream().map(BreakdownSectionData::key).toList());
        assertEquals(List.of("Open lots", "History"), r.sections().stream().map(BreakdownSectionData::label).toList());
        r.sections().forEach(s -> {
            assertNull(s.rowAction());
            assertNull(s.rowBreakdownDatasource());
        });
        TableData lotsPage = (TableData) r.sections().get(0).table();
        assertEquals(new TableData.Page(0, 2, 3, 2), lotsPage.page());
        assertEquals(List.of("0", "1"), lotsPage.rows().stream().map(row -> row.get("id")).toList());
        TableData historyPage = (TableData) r.sections().get(1).table();
        assertEquals(new TableData.Page(0, 2, 0, 1), historyPage.page());
        assertTrue(historyPage.rows().isEmpty());
    }

    @Test
    void lotsSectionLabelsOriginsAndSumsExactlyToQuantityAndInvested() {
        CorporateAction demerger = corporateAction(CorporateActionType.demerger, "Parent Corp");
        CorporateAction merger = corporateAction(CorporateActionType.merger, "HDFC Ltd");
        // Unrounded costs 33.33333333, 33.33333333, 33.33333334 sum to 100.00000000; each rounds to
        // 33.3333 so HALF_UP per lot would show 99.9999 against an invested amount of 100.0000.
        List<OpenLot> lots = List.of(
                lot(LocalDate.of(2024, 1, 1), LotSource.BUY, "1", "33.33333333"),
                lot(LocalDate.of(2024, 2, 1), LotSource.INTRADAY_NETTED_DELIVERY, "1", "33.33333333"),
                lot(LocalDate.of(2024, 3, 1), LotSource.corporateAction(demerger), "0.5", "66.66666668"),
                lot(LocalDate.of(2024, 4, 1), LotSource.corporateAction(merger), "0.5", "0"));
        stubTrace(open("3", "100.0000", "100", "0", null), lots, List.of());

        TableData t = (TableData) provider.section(rowId, "lots", 0, 25);

        assertEquals(List.of("buyDate", "origin", "quantity", "costPerUnit", "cost"),
                t.columns().stream().map(TableData.Column::key).toList());
        assertEquals(List.of("Buy", "Delivery after intraday netting", "From demerger of Parent Corp", "From merger of HDFC Ltd"),
                t.rows().stream().map(row -> row.get("origin")).toList());
        assertEquals(LocalDate.of(2024, 3, 1), t.rows().get(2).get("buyDate"));
        assertEquals(new BigDecimal("66.66666668"), t.rows().get(2).get("costPerUnit"));
        assertEquals(new BigDecimal("3.00000000"), sum(t.rows(), "quantity"));
        assertEquals(new BigDecimal("100.0000"), sum(t.rows(), "cost"));
        assertEquals(new BigDecimal("33.3334"), t.rows().get(2).get("cost"));
    }

    @Test
    void historySectionLabelsEveryEventInEngineOrder() {
        Instrument hdfcBank = instrument("HDFC Bank", InstrumentType.stock);
        CorporateAction split = corporateAction(CorporateActionType.split, null);
        split.setRatioFrom(1);
        split.setRatioTo(2);
        CorporateAction bonus = corporateAction(CorporateActionType.bonus, null);
        bonus.setRatioFrom(2);
        bonus.setRatioTo(3);
        CorporateAction carve = corporateAction(CorporateActionType.demerger, null);
        carve.setCostAllocationPct(new BigDecimal("30.00"));
        CorporateAction noCarve = corporateAction(CorporateActionType.demerger, null);
        CorporateAction mergedInto = corporateAction(CorporateActionType.merger, null);
        mergedInto.setTargetInstrument(hdfcBank);
        CorporateAction mergedUnknown = corporateAction(CorporateActionType.merger, null);
        CorporateAction receivedDemerger = corporateAction(CorporateActionType.demerger, "Parent Corp");
        CorporateAction receivedMerger = corporateAction(CorporateActionType.merger, "HDFC Ltd");
        LocalDate d = LocalDate.of(2024, 1, 1);
        List<Event> events = List.of(
                event(d, EventKind.BUY, null, "10", "100", "10", "10"),
                event(d, EventKind.SELL, null, "4", "110", "-4", "6"),
                event(d, EventKind.INTRADAY_NETTED, null, "5.50", null, "0", "6"),
                event(d, EventKind.DELIVERY_BUY, null, "2", "101.5", "2", "8"),
                event(d, EventKind.DELIVERY_SELL, null, "1", "102", "-1", "7"),
                event(d, EventKind.CORPORATE_ACTION, split, null, null, "7", "14"),
                event(d, EventKind.CORPORATE_ACTION, bonus, null, null, "7", "21"),
                event(d, EventKind.CORPORATE_ACTION, carve, null, null, "0", "21"),
                event(d, EventKind.CORPORATE_ACTION, noCarve, null, null, "0", "21"),
                event(d, EventKind.CORPORATE_ACTION, mergedInto, null, null, "-21", "0"),
                event(d, EventKind.CORPORATE_ACTION, mergedUnknown, null, null, "0", "0"),
                event(d, EventKind.RECEIVED_FROM_CORPORATE_ACTION, receivedDemerger, "5", null, "5", "5"),
                event(d, EventKind.RECEIVED_FROM_CORPORATE_ACTION, receivedMerger, "3", null, "3", "8"));
        stubTrace(open("8", "800", "800", "0", null), List.of(), events);

        TableData t = (TableData) provider.section(rowId, "history", 0, 25);

        assertEquals(List.of("date", "event", "quantityChange", "price", "quantityAfter"),
                t.columns().stream().map(TableData.Column::key).toList());
        assertEquals(List.of(
                "Buy",
                "Sell",
                "Intraday trades netted (5.5 squared off)",
                "Delivery buy after intraday netting",
                "Delivery sell after intraday netting",
                "Split 1:2",
                "Bonus 1:2",
                "Demerger cost carve 30%",
                "Demerger (no cost carved)",
                "Merged into HDFC Bank",
                "Merged",
                "Received from demerger of Parent Corp",
                "Received from merger of HDFC Ltd"),
                t.rows().stream().map(row -> row.get("event")).toList());
        Map<String, Object> sell = t.rows().get(1);
        assertEquals(d, sell.get("date"));
        assertEquals(new BigDecimal("-4"), sell.get("quantityChange"));
        assertEquals(new BigDecimal("110"), sell.get("price"));
        assertEquals(new BigDecimal("6"), sell.get("quantityAfter"));
    }

    @Test
    void splitOrBonusWithoutARatioIsNamedByTypeOnly() {
        CorporateAction split = corporateAction(CorporateActionType.split, null);
        CorporateAction bonus = corporateAction(CorporateActionType.bonus, null);
        bonus.setRatioFrom(1);
        LocalDate d = LocalDate.of(2024, 1, 1);
        stubTrace(open("1", "1", "1", "0", null), List.of(), List.of(
                event(d, EventKind.CORPORATE_ACTION, split, null, null, "0", "0"),
                event(d, EventKind.CORPORATE_ACTION, bonus, null, null, "0", "0")));

        TableData t = (TableData) provider.section(rowId, "history", 0, 25);

        assertEquals(List.of("Split", "Bonus"), t.rows().stream().map(row -> row.get("event")).toList());
    }

    @Test
    void sectionPagesAreSlicedFromTheFullList() {
        List<OpenLot> lots = List.of(
                lot(LocalDate.of(2024, 1, 1), LotSource.BUY, "1", "10"),
                lot(LocalDate.of(2024, 2, 1), LotSource.BUY, "1", "10"),
                lot(LocalDate.of(2024, 3, 1), LotSource.BUY, "1", "10"));
        stubTrace(open("3", "30", "30", "0", null), lots, List.of());

        TableData second = (TableData) provider.section(rowId, "lots", 1, 2);
        assertEquals(new TableData.Page(1, 2, 3, 2), second.page());
        assertEquals(List.of("2"), second.rows().stream().map(row -> row.get("id")).toList());
        assertEquals(LocalDate.of(2024, 3, 1), second.rows().get(0).get("buyDate"));

        TableData beyond = (TableData) provider.section(rowId, "lots", 5, 2);
        assertTrue(beyond.rows().isEmpty());
        assertEquals(new TableData.Page(5, 2, 3, 2), beyond.page());
    }

    // ---- apportioned rounding ----

    @Test
    void apportionLandsOnTheRoundedTotalGivingUnitsToTheLargestRemaindersFirst() {
        List<BigDecimal> rounded = PositionsBreakdownProvider.apportion(List.of(
                new BigDecimal("1.004"), new BigDecimal("1.006"), new BigDecimal("1.006"), new BigDecimal("1.004")), 2);

        // Σ = 4.02; floors sum to 4.00, so two units go to the two .006 values (earliest first on ties).
        assertEquals(List.of(new BigDecimal("1.00"), new BigDecimal("1.01"), new BigDecimal("1.01"), new BigDecimal("1.00")), rounded);
    }

    @Test
    void apportionBreaksRemainderTiesByPosition() {
        List<BigDecimal> rounded = PositionsBreakdownProvider.apportion(List.of(
                new BigDecimal("0.005"), new BigDecimal("0.005"), new BigDecimal("0.005")), 2);

        // Σ = 0.015 → 0.02 (HALF_UP): the first two values get the units.
        assertEquals(List.of(new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.00")), rounded);
    }

    @Test
    void apportionKeepsValuesAlreadyAtScale() {
        assertEquals(List.of(new BigDecimal("2.50"), new BigDecimal("1.25")),
                PositionsBreakdownProvider.apportion(List.of(new BigDecimal("2.5"), new BigDecimal("1.25")), 2));
        assertEquals(List.of(), PositionsBreakdownProvider.apportion(List.of(), 2));
    }

    // ---- helpers ----

    /** start ± add/subtract steps = equals = total, in exact BigDecimal arithmetic. */
    private static void assertChainReconciles(RowBreakdownResponse r) {
        BigDecimal running = null;
        BigDecimal equals = null;
        for (BreakdownStep s : r.steps()) {
            switch (s.op()) {
                case "start" -> running = s.amount();
                case "add" -> running = running.add(s.amount());
                case "subtract" -> running = running.subtract(s.amount());
                case "equals" -> equals = s.amount();
                default -> { }
            }
        }
        assertNotNull(equals);
        assertEquals(0, running.compareTo(equals));
        assertEquals(0, equals.compareTo(r.total()));
    }

    private String latestPriceDetail(LocalDate asOf, PriceSource source) {
        stubTrace(position("10", "100", "1000", new BigDecimal("120"), asOf, source, "1200", "200", null, null), List.of(), List.of());
        return provider.breakdown(rowId, 25).steps().stream()
                .filter(s -> s.label().equals("Latest price")).findFirst().orElseThrow().detail();
    }

    private String kindLabelFor(InstrumentType type) {
        holding.getInstrument().setType(type);
        stubTrace(open("10", "1000", "1200", "200", new BigDecimal("120")), List.of(), List.of());
        return provider.breakdown(rowId, 25).kindLabel();
    }

    private void stubTrace(HoldingPosition position, List<OpenLot> lots, List<Event> events) {
        when(investmentService.traceHoldingPosition(holding.getId())).thenReturn(new HoldingTrace(position, lots, events));
    }

    private HoldingPosition open(String qty, String cost, String currentValue, String unrealized, BigDecimal price) {
        BigDecimal avg = new BigDecimal(cost).divide(new BigDecimal(qty), 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return position(qty, avg.toPlainString(), cost, price, price != null ? PRICE_DATE : null,
                price != null ? PriceSource.YAHOO : null, currentValue, unrealized, null, null);
    }

    private HoldingPosition position(String qty, String avgCost, String cost, BigDecimal price, LocalDate asOf, PriceSource source,
                                     String currentValue, String unrealized, String mergedIntoName, LocalDate mergedIntoDate) {
        return new HoldingPosition(holding, new BigDecimal(qty), new BigDecimal(avgCost), new BigDecimal(cost),
                price, asOf, source,
                currentValue != null ? new BigDecimal(currentValue) : null,
                unrealized != null ? new BigDecimal(unrealized) : null,
                null, new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("3"), new BigDecimal("12"),
                null, null, mergedIntoName, mergedIntoDate);
    }

    private static HoldingPosition withIntradayRealized(HoldingPosition p, BigDecimal intraday) {
        return new HoldingPosition(p.holding(), p.openQty(), p.avgCost(), p.openCost(), p.latestPrice(), p.priceAsOf(),
                p.priceSource(), p.currentValue(), p.unrealized(), p.unrealizedPercent(), p.realized(), intraday,
                p.totalCharges(), p.dividends(), p.xirr(), p.absoluteReturnPercent(), p.mergedIntoName(), p.mergedIntoDate());
    }

    private static OpenLot lot(LocalDate buyDate, LotSource source, String qty, String costPerUnit) {
        return new OpenLot(buyDate, source, new BigDecimal(qty), new BigDecimal(costPerUnit));
    }

    private static Event event(LocalDate date, EventKind kind, CorporateAction action, String qty, String price,
                               String change, String after) {
        return new Event(date, kind, action, qty != null ? new BigDecimal(qty) : null, price != null ? new BigDecimal(price) : null,
                new BigDecimal(change), new BigDecimal(after));
    }

    private static CorporateAction corporateAction(CorporateActionType type, String instrumentName) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setType(type);
        if (instrumentName != null) {
            ca.setInstrument(instrument(instrumentName, InstrumentType.stock));
        }
        return ca;
    }

    private static Instrument instrument(String name, InstrumentType type) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setType(type);
        return i;
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> (BigDecimal) row.get(key)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
