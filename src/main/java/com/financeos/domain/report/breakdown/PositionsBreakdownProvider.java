package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.HoldingTrace;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Explains a {@code positions} row (one holding): its current value as cost of the open lots plus
 * the unrealised gain, the open lots left by FIFO, and the timeline the engine applied. Everything
 * comes from {@link InvestmentService#traceHoldingPosition}, the same engine run that produces the
 * row, so the breakdown can never disagree with it.
 */
@Component
@Transactional(readOnly = true)
public class PositionsBreakdownProvider implements RowBreakdownProvider {

    private static final String LOTS = "lots";
    private static final String HISTORY = "history";

    /** Scales the open lots are shown at; the same the engine rounds openQty / openCost to. */
    private static final int QUANTITY_SCALE = 8;
    private static final int COST_SCALE = 4;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<TableData.Column> LOT_COLUMNS = List.of(
            new TableData.Column("buyDate", "Buy date", "date", null),
            new TableData.Column("origin", "Origin", "string", null),
            new TableData.Column("quantity", "Quantity", "number", "number"),
            new TableData.Column("costPerUnit", "Cost per unit", "number", "currency"),
            new TableData.Column("cost", "Cost", "number", "currency"));

    private static final List<TableData.Column> HISTORY_COLUMNS = List.of(
            new TableData.Column("date", "Date", "date", null),
            new TableData.Column("event", "Event", "string", null),
            new TableData.Column("quantityChange", "Quantity change", "number", "number"),
            new TableData.Column("price", "Price", "number", "currency"),
            new TableData.Column("quantityAfter", "Quantity after", "number", "number"));

    private final InvestmentService investmentService;

    public PositionsBreakdownProvider(InvestmentService investmentService) {
        this.investmentService = investmentService;
    }

    @Override
    public String datasource() {
        return "positions";
    }

    @Override
    public RowBreakdownResponse breakdown(String rowId, int size) {
        HoldingTrace trace = trace(rowId);
        InstrumentOverrides ov = overrides();
        HoldingPosition p = trace.position();
        Holding holding = p.holding();
        boolean open = p.openQty().signum() > 0;
        BigDecimal total = open ? p.currentValue() : BigDecimal.ZERO;

        List<BreakdownSectionData> sections = List.of(
                new BreakdownSectionData(LOTS, "Open lots", null, null, lotsTable(trace, 0, size, null, ov)),
                new BreakdownSectionData(HISTORY, "History", null, null, historyTable(trace, 0, size, null, ov)));

        return new RowBreakdownResponse(
                datasource(),
                rowId,
                ov.name(holding.getInstrument()),
                holding.getBrokerAccount().getName(),
                kindLabel(ov.type(holding.getInstrument())),
                total,
                "Current value",
                "currency",
                AppTime.today(),
                steps(p, open, total),
                sections,
                notes(p));
    }

    /** Declared here (not only the interface default) so it runs in this bean's transaction. */
    @Override
    public ReportData section(String rowId, String section, int page, int size) {
        return section(rowId, section, page, size, null);
    }

    @Override
    public ReportData section(String rowId, String section, int page, int size, @Nullable SortClause sort) {
        HoldingTrace trace = trace(rowId);
        InstrumentOverrides ov = overrides();
        return switch (section) {
            case LOTS -> lotsTable(trace, page, size, sort, ov);
            case HISTORY -> historyTable(trace, page, size, sort, ov);
            default -> throw new ResourceNotFoundException("Breakdown section", section);
        };
    }

    /** The current user's instrument overrides (names in the title and the corporate-action labels). */
    private InstrumentOverrides overrides() {
        return InstrumentOverrides.orNone(investmentService.instrumentOverrides());
    }

    private HoldingTrace trace(String rowId) {
        UUID holdingId;
        try {
            holdingId = UUID.fromString(rowId);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Holding", rowId);
        }
        return investmentService.traceHoldingPosition(holdingId);
    }

    /**
     * Cost of open lots ± unrealised = current value. The engine derives unrealised as
     * {@code currentValue − openCost}, so the chain closes exactly; a residual would only come
     * from an engine change and {@link BreakdownChain} surfaces it as an explicit rounding step.
     */
    private List<BreakdownStep> steps(HoldingPosition p, boolean open, BigDecimal total) {
        BreakdownChain chain = new BreakdownChain(total);
        if (open) {
            chain.info("Open quantity", null, p.openQty(), "number")
                    .info("Average cost", null, p.avgCost(), BreakdownChain.CURRENCY);
        } else {
            chain.info("Position closed", mergedDetail(p), null, null);
        }
        chain.start("Cost of open lots", p.openCost());
        if (open && p.latestPrice() != null && p.unrealized().signum() != 0) {
            chain.term(p.unrealized().signum() > 0 ? "Unrealised gain" : "Unrealised loss", p.unrealized());
        }

        List<BreakdownStep> steps = new ArrayList<>(chain.close("Current value", total, "holding " + p.holding().getId()));
        if (open) {
            steps.add(p.latestPrice() != null
                    ? info("Latest price", priceDetail(p), p.latestPrice())
                    : new BreakdownStep(BreakdownChain.INFO, "No price — valued at cost", null, null, null));
        }
        steps.add(info("Realised P&L", null, p.realized()));
        steps.add(info("Dividends", null, p.dividends()));
        steps.add(info("Charges", null, p.totalCharges()));
        return steps;
    }

    private static BreakdownStep info(String label, String detail, BigDecimal amount) {
        return new BreakdownStep(BreakdownChain.INFO, label, detail, amount, BreakdownChain.CURRENCY);
    }

    private static List<String> notes(HoldingPosition p) {
        List<String> notes = new ArrayList<>();
        notes.add("Cost is the traded price of the lots still open after first-in, first-out matching; charges are not included.");
        if (p.intradayRealized().signum() != 0) {
            notes.add("Intraday P&L of " + MessageFormat.money(p.intradayRealized()) + " is not part of Realised P&L.");
        }
        return notes;
    }

    private static String mergedDetail(HoldingPosition p) {
        return p.mergedIntoName() != null
                ? "Merged into " + p.mergedIntoName() + " on " + DATE.format(p.mergedIntoDate())
                : null;
    }

    private static String priceDetail(HoldingPosition p) {
        List<String> parts = new ArrayList<>();
        if (p.priceAsOf() != null) {
            parts.add(DATE.format(p.priceAsOf()));
        }
        if (p.priceSource() != null) {
            parts.add(sourceLabel(p.priceSource()));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String sourceLabel(PriceSource source) {
        return switch (source) {
            case AMFI -> "AMFI";
            case YAHOO -> "Yahoo Finance";
            case MANUAL -> "Manual";
        };
    }

    private static String kindLabel(InstrumentType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case stock -> "Stock";
            case mutual_fund -> "Mutual fund";
            case etf -> "ETF";
        };
    }

    /**
     * Open lots, oldest first. Quantity and cost are rounded so that each column sums exactly to
     * the row's quantity and invested amount: both are the unrounded lot sums rounded once, so the
     * per-lot rounding is apportioned (largest remainder) to land on that same figure.
     */
    private TableData lotsTable(HoldingTrace trace, int page, int size, @Nullable SortClause sort,
                                InstrumentOverrides ov) {
        List<HoldingTrace.OpenLot> lots = trace.openLots();
        List<BigDecimal> quantities = apportion(lots.stream().map(HoldingTrace.OpenLot::quantity).toList(), QUANTITY_SCALE);
        List<BigDecimal> costs = apportion(lots.stream().map(HoldingTrace.OpenLot::cost).toList(), COST_SCALE);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            HoldingTrace.OpenLot lot = lots.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(i));
            row.put("buyDate", lot.buyDate());
            row.put("origin", originLabel(lot.source(), ov));
            row.put("quantity", quantities.get(i));
            row.put("costPerUnit", lot.costPerUnit());
            row.put("cost", costs.get(i));
            rows.add(row);
        }
        return BreakdownTables.sorted(LOT_COLUMNS, rows, sort, page, size);
    }

    private TableData historyTable(HoldingTrace trace, int page, int size, @Nullable SortClause sort,
                                   InstrumentOverrides ov) {
        List<HoldingTrace.Event> events = trace.events();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            HoldingTrace.Event e = events.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(i));
            row.put("date", e.date());
            row.put("event", eventLabel(e, ov));
            row.put("quantityChange", e.quantityChange());
            row.put("price", e.price());
            row.put("quantityAfter", e.quantityAfter());
            rows.add(row);
        }
        return BreakdownTables.sorted(HISTORY_COLUMNS, rows, sort, page, size);
    }

    private static String originLabel(HoldingTrace.LotSource source, InstrumentOverrides ov) {
        return switch (source.origin()) {
            case BUY -> "Buy";
            case INTRADAY_NETTED_DELIVERY -> "Delivery after intraday netting";
            case CORPORATE_ACTION -> "From " + receivedFrom(source.action(), ov);
            case BONUS -> corporateActionLabel(source.action(), ov);
        };
    }

    private static String eventLabel(HoldingTrace.Event e, InstrumentOverrides ov) {
        return switch (e.kind()) {
            case BUY -> "Buy";
            case SELL -> "Sell";
            case INTRADAY_NETTED -> "Intraday trades netted (" + plain(e.quantity()) + " squared off)";
            case DELIVERY_BUY -> "Delivery buy after intraday netting";
            case DELIVERY_SELL -> "Delivery sell after intraday netting";
            case CORPORATE_ACTION -> corporateActionLabel(e.action(), ov);
            case RECEIVED_FROM_CORPORATE_ACTION -> "Received from " + receivedFrom(e.action(), ov);
        };
    }

    /** "demerger of X" / "merger of X", X being the instrument the shares came from. */
    private static String receivedFrom(CorporateAction action, InstrumentOverrides ov) {
        String kind = action.getType() == CorporateActionType.merger ? "merger" : "demerger";
        return kind + " of " + ov.name(action.getInstrument());
    }

    private static String corporateActionLabel(CorporateAction action, InstrumentOverrides ov) {
        Integer from = action.getRatioFrom();
        Integer to = action.getRatioTo();
        boolean hasRatio = from != null && to != null;
        return switch (action.getType()) {
            case split -> hasRatio ? "Split " + from + ":" + to : "Split";
            // Bonus is stored as held → held-after (1:1 bonus = 1 → 2); show it as new:held.
            case bonus -> hasRatio ? "Bonus " + (to - from) + ":" + from : "Bonus";
            case demerger -> action.getCostAllocationPct() != null && action.getCostAllocationPct().signum() > 0
                    ? "Demerger cost carve " + plain(action.getCostAllocationPct()) + "%"
                    : "Demerger (no cost carved)";
            case merger -> action.getTargetInstrument() != null
                    ? "Merged into " + ov.name(action.getTargetInstrument())
                    : "Merged";
        };
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * Rounds each value to {@code scale} so the rounded values sum exactly to the unrounded total
     * rounded HALF_UP to {@code scale}: values are rounded down, then the remaining units go one
     * each to the values with the largest dropped remainders (earliest first on ties).
     */
    static List<BigDecimal> apportion(List<BigDecimal> values, int scale) {
        BigDecimal target = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(scale, RoundingMode.HALF_UP);
        List<BigDecimal> rounded = new ArrayList<>(values.stream().map(v -> v.setScale(scale, RoundingMode.FLOOR)).toList());
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
        int units = target.subtract(rounded.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).divide(unit).intValueExact();
        List<Integer> byRemainder = IntStream.range(0, values.size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> values.get(i).subtract(rounded.get(i))).reversed()
                        .thenComparing(i -> i))
                .toList();
        for (int k = 0; k < units; k++) {
            int i = byRemainder.get(k);
            rounded.set(i, rounded.get(i).add(unit));
        }
        return rounded;
    }
}
