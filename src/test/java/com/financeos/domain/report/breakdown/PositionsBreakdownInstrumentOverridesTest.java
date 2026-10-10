package com.financeos.domain.report.breakdown;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.HoldingTrace;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The positions breakdown names the holding and the corporate actions' instruments as the reader does. */
class PositionsBreakdownInstrumentOverridesTest {

    private static final LocalDate D = LocalDate.of(2025, 6, 1);

    private InvestmentService investmentService;
    private PositionsBreakdownProvider provider;
    private Holding holding;
    private Instrument parent;
    private Instrument target;
    private final UUID user = UUID.randomUUID();

    private static Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setType(InstrumentType.stock);
        return i;
    }

    @BeforeEach
    void setUp() {
        investmentService = mock(InvestmentService.class);
        provider = new PositionsBreakdownProvider(investmentService);
        Account broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        Instrument held = instrument("Catalog Child");
        parent = instrument("Catalog Parent");
        target = instrument("Catalog Target");
        holding = new Holding(broker, held, null);
        holding.setId(UUID.randomUUID());

        CorporateAction demerger = new CorporateAction();
        demerger.setType(CorporateActionType.demerger);
        demerger.setInstrument(parent);
        demerger.setTargetInstrument(held);
        CorporateAction merger = new CorporateAction();
        merger.setType(CorporateActionType.merger);
        merger.setInstrument(held);
        merger.setTargetInstrument(target);

        HoldingPosition position = new HoldingPosition(holding, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ONE, D, PriceSource.YAHOO, BigDecimal.TEN, BigDecimal.ZERO, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        List<HoldingTrace.OpenLot> lots = List.of(new HoldingTrace.OpenLot(D, HoldingTrace.LotSource.corporateAction(demerger),
                BigDecimal.TEN, BigDecimal.ONE));
        List<HoldingTrace.Event> events = List.of(
                new HoldingTrace.Event(D, HoldingTrace.EventKind.RECEIVED_FROM_CORPORATE_ACTION, demerger, BigDecimal.TEN,
                        null, BigDecimal.TEN, BigDecimal.TEN),
                new HoldingTrace.Event(D.plusDays(1), HoldingTrace.EventKind.CORPORATE_ACTION, merger, null, null,
                        BigDecimal.ZERO, BigDecimal.TEN));
        when(investmentService.traceHoldingPosition(holding.getId())).thenReturn(new HoldingTrace(position, lots, events));

        UserInstrumentOverride child = new UserInstrumentOverride(user, held.getId());
        child.setName("My Child");
        child.setType(InstrumentType.etf);
        UserInstrumentOverride p = new UserInstrumentOverride(user, parent.getId());
        p.setName("My Parent");
        UserInstrumentOverride t = new UserInstrumentOverride(user, target.getId());
        t.setName("My Target");
        when(investmentService.instrumentOverrides()).thenReturn(InstrumentOverrides.of(List.of(child, p, t)));
    }

    @Test
    void theTitleKindAndLabelsUseTheReadersNames() {
        RowBreakdownResponse r = provider.breakdown(holding.getId().toString(), 25);
        assertEquals("My Child", r.title());
        assertEquals("ETF", r.kindLabel());

        TableData history = (TableData) provider.section(holding.getId().toString(), "history", 0, 25);
        List<Map<String, Object>> rows = history.rows();
        assertEquals("Received from demerger of My Parent", rows.get(0).get("event"));
        assertEquals("Merged into My Target", rows.get(1).get("event"));

        TableData lots = (TableData) provider.section(holding.getId().toString(), "lots", 0, 25);
        assertEquals("From demerger of My Parent", lots.rows().get(0).get("origin"));
    }

    @Test
    void withoutOverridesTheCatalogNamesShow() {
        when(investmentService.instrumentOverrides()).thenReturn(null);
        RowBreakdownResponse r = provider.breakdown(holding.getId().toString(), 25);
        assertEquals("Catalog Child", r.title());
        assertEquals("Stock", r.kindLabel());
        List<Map<String, Object>> rows = ((TableData) provider.section(holding.getId().toString(), "history", 0, 25)).rows();
        assertEquals("Merged into Catalog Target", rows.get(1).get("event"));
    }
}
