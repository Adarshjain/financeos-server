package com.financeos.domain.instrument.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriceCoverageMonitorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final InstrumentPriceRepository prices = mock(InstrumentPriceRepository.class);
    private PriceCoverageMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new PriceCoverageMonitor(registry, holdings, actions, instruments, prices);
        when(holdings.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of());
        when(prices.findLatestPriceDates(any())).thenReturn(List.of());
    }

    private static Instrument stock(String symbol, String status) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setSymbol(symbol);
        i.setYahooSymbol(symbol + ".NS");
        i.setPriceStatus(status);
        return i;
    }

    private void held(List<Instrument> targets) {
        when(actions.findDistinctMergerTargetInstrumentIds()).thenReturn(targets.stream().map(Instrument::getId).toList());
        when(instruments.findAllById(any())).thenReturn(targets);
    }

    private void latest(Instrument i, LocalDate d) {
        List<Object[]> rows = new ArrayList<>(prices.findLatestPriceDates(List.of()));
        rows.add(new Object[]{i.getId().toString(), d});
        when(prices.findLatestPriceDates(any())).thenReturn(rows);
    }

    private double gauge() {
        return registry.get("financeos.price.held_unpriced").gauge().value();
    }

    @Test
    void anUnpricedMergerTargetIsCounted() {
        Instrument priced = stock("ACME", null);
        Instrument target = stock("TMCV", null);
        held(List.of(priced, target));
        latest(priced, LocalDate.of(2026, 11, 13));

        assertThat(monitor.check()).containsExactly("TMCV");
        assertThat(gauge()).isEqualTo(1.0);
    }

    @Test
    void aNotTradingOrDelistedInstrumentIsIgnored() {
        held(List.of(stock("OLD", "not_trading"), stock("GONE", "delisted")));

        assertThat(monitor.check()).isEmpty();
        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    void aRecentlyPricedInstrumentIsNotCounted() {
        Instrument priced = stock("TMPV", null);
        held(List.of(priced));
        latest(priced, LocalDate.of(2026, 11, 13));

        assertThat(monitor.check()).isEmpty();
        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    void anInstrumentWithNoPriceSymbolIsIgnored() {
        Instrument noSymbol = new Instrument();
        noSymbol.setId(UUID.randomUUID());
        noSymbol.setSymbol("X");
        held(List.of(noSymbol));

        assertThat(monitor.check()).isEmpty();
    }

    @Test
    void theGaugeFallsBackToZeroOnceEverythingIsPriced() {
        Instrument a = stock("A", null);
        Instrument other = stock("B", null);
        held(List.of(a, other));
        latest(other, LocalDate.of(2026, 11, 13));
        monitor.check();
        assertThat(gauge()).isEqualTo(1.0);
        latest(a, LocalDate.of(2026, 11, 13));

        monitor.check();

        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    void manyOffendersAreAllCountedAndTheLogIsCapped() {
        List<Instrument> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(stock("S" + i, null));
        }
        Instrument current = stock("CUR", null);
        many.add(current);
        held(many);
        latest(current, LocalDate.of(2026, 11, 13));

        assertThat(monitor.check()).hasSize(12);
        assertThat(gauge()).isEqualTo(12.0);
    }

    @Test
    void aWeekendGapIsNotUnpriced() {
        Instrument a = stock("A", null);
        Instrument b = stock("B", null);
        held(List.of(a, b));
        latest(a, LocalDate.of(2026, 11, 13));
        latest(b, LocalDate.of(2026, 11, 13));

        assertThat(monitor.check()).isEmpty();
        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    void oneStaleByTwoTradingDaysWhileOthersAreCurrentIsCounted() {
        Instrument current = stock("CUR", null);
        Instrument stale = stock("OLD", null);
        Instrument oneBehind = stock("ONE", null);
        held(List.of(current, stale, oneBehind));
        latest(current, LocalDate.of(2026, 11, 13));
        latest(stale, LocalDate.of(2026, 11, 11));
        latest(oneBehind, LocalDate.of(2026, 11, 12));

        assertThat(monitor.check()).containsExactly("OLD");
    }

    @Test
    void aKindWithNoPriceAtAllCountsNone() {
        held(List.of(stock("A", null), stock("B", null)));

        assertThat(monitor.check()).isEmpty();
        assertThat(gauge()).isEqualTo(0.0);
    }

    @Test
    void kindsAreJudgedAgainstTheirOwnMarketDate() {
        Instrument stockA = stock("A", null);
        Instrument fund = stock("FUND", null);
        fund.setType(com.financeos.domain.instrument.InstrumentType.mutual_fund);
        Instrument fundOld = stock("FUNDOLD", null);
        fundOld.setType(com.financeos.domain.instrument.InstrumentType.mutual_fund);
        held(List.of(stockA, fund, fundOld));
        latest(stockA, LocalDate.of(2026, 11, 13));
        latest(fund, LocalDate.of(2026, 11, 10));
        latest(fundOld, LocalDate.of(2026, 11, 7));

        assertThat(monitor.check()).containsExactly("FUNDOLD");
    }

    private void exDate(Instrument target, LocalDate ex) {
        when(actions.findEarliestExDatesByTarget(any())).thenReturn(List.<Object[]>of(new Object[]{target.getId().toString(), ex}));
    }

    @Test
    void aNeverPricedTargetIsGivenSixtyDaysToListButOnlyThen() {
        Instrument priced = stock("ACME", null);
        Instrument fresh = stock("NEWCO", null);
        held(List.of(priced, fresh));
        latest(priced, LocalDate.of(2026, 11, 13));
        exDate(fresh, com.financeos.core.time.AppTime.today().minusDays(30));

        assertThat(monitor.check()).as("ex-date 30 days ago: not listed yet, not counted").isEmpty();

        exDate(fresh, com.financeos.core.time.AppTime.today().minusDays(61));

        assertThat(monitor.check()).as("ex-date 61 days ago: counted").containsExactly("NEWCO");
    }

    @Test
    void aTargetThatHadPricesAndWentStaleCountsAtOnce() {
        Instrument current = stock("CUR", null);
        Instrument target = stock("TMPV", null);
        held(List.of(current, target));
        latest(current, LocalDate.of(2026, 11, 13));
        latest(target, LocalDate.of(2026, 11, 10));
        exDate(target, com.financeos.core.time.AppTime.today().minusDays(5));

        assertThat(monitor.check()).containsExactly("TMPV");
    }
}
