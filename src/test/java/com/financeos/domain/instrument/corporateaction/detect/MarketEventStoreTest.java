package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.corporateaction.detect.NseCorporateActionsClient.Row;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketEventStoreTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    private final NseCorporateActionsClient client = mock(NseCorporateActionsClient.class);
    private final MarketCorporateEventRepository events = mock(MarketCorporateEventRepository.class);
    private final MarketEventCoverageRepository coverage = mock(MarketEventCoverageRepository.class);
    private final MarketEventStore store = new MarketEventStore(client, events, coverage);

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        store.setPauseMs(0);
        when(coverage.save(any())).thenAnswer(i -> i.getArgument(0));
        when(events.findWindow(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static NseCorporateActionsClient.Result ok(Row... rows) {
        return new NseCorporateActionsClient.Result(NseCorporateActionsClient.Status.OK, List.of(rows));
    }

    private static NseCorporateActionsClient.Result failed() {
        return new NseCorporateActionsClient.Result(NseCorporateActionsClient.Status.FAILED, List.of());
    }

    private static MarketEventCoverage covered(LocalDate from, LocalDate to) {
        MarketEventCoverage c = new MarketEventCoverage();
        c.setSource("nse");
        c.setCoveredFrom(from);
        c.setCoveredTo(to);
        return c;
    }

    @Test
    void refreshKeepsOnlyStructuralEquityRowsNotStoredYet() {
        MarketCorporateEvent known = new MarketCorporateEvent();
        known.setSymbol("ACMEIND");
        known.setExDate(LocalDate.of(2026, 10, 28));
        known.setSubject("Bonus 1:1");
        when(events.findWindow(any(), any(), any())).thenReturn(List.of(known));
        when(client.fetchWindow(any(), any())).thenReturn(ok(
                new Row("ACMEIND", "INE000X01011", "Acme", "EQ", LocalDate.of(2026, 10, 28), "Bonus 1:1"),
                new Row("BRAVOW", "INE000X01029", "Bravo", "EQ", LocalDate.of(2026, 10, 29), "Bonus 1:2"),
                new Row("BRAVOW", "INE000X01029", "Bravo", "EQ", LocalDate.of(2026, 10, 29), "Bonus 1:2"),
                new Row("CHARLIE", null, "Charlie", "EQ", LocalDate.of(2026, 10, 30), "Dividend - Rs 3 Per Share"),
                new Row("DELTA", null, "Delta", "BE", LocalDate.of(2026, 10, 30), "Bonus 1:1"),
                new Row("ECHO", null, "Echo", "EQ", null, "Bonus 1:1")));

        assertThat(store.refreshWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketCorporateEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(events).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        MarketCorporateEvent e = saved.getValue().get(0);
        assertThat(e.getSymbol()).isEqualTo("BRAVOW");
        assertThat(e.getParsedType()).isEqualTo("bonus");
        assertThat(e.getRatioFrom()).isEqualTo(2);
        assertThat(e.getRatioTo()).isEqualTo(3);
        assertThat(e.isClean()).isTrue();
    }

    @Test
    void refreshReturnsFalseOnFailure() {
        when(client.fetchWindow(any(), any())).thenReturn(failed());
        assertThat(store.refreshWindow(TODAY.minusDays(5), TODAY)).isFalse();
        verify(events, never()).saveAll(any());
    }

    @Test
    void firstCoverageLoadsLast30Days() {
        when(coverage.findById("nse")).thenReturn(Optional.empty());
        when(client.fetchWindow(any(), any())).thenReturn(ok());

        store.ensureCoverage(TODAY.minusDays(10));

        verify(client).fetchWindow(TODAY.minusDays(30), TODAY.plusDays(1));
        ArgumentCaptor<MarketEventCoverage> c = ArgumentCaptor.forClass(MarketEventCoverage.class);
        verify(coverage).save(c.capture());
        assertThat(c.getValue().getCoveredFrom()).isEqualTo(TODAY.minusDays(30));
        assertThat(c.getValue().getCoveredTo()).isEqualTo(TODAY);
    }

    @Test
    void olderNeedLoadsGapOneYearAtATimeNewestFirst() {
        MarketEventCoverage cov = covered(LocalDate.of(2026, 10, 12), TODAY);
        when(coverage.findById("nse")).thenReturn(Optional.of(cov));
        when(client.fetchWindow(any(), any())).thenReturn(ok());

        store.ensureCoverage(LocalDate.of(2024, 5, 3));

        verify(client).fetchWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 11));
        verify(client).fetchWindow(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        verify(client).fetchWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        verify(client, times(3)).fetchWindow(any(), any());
        assertThat(cov.getCoveredFrom()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void gapLoadStopsAtFirstFailureKeepingProgress() {
        MarketEventCoverage cov = covered(LocalDate.of(2026, 10, 12), TODAY);
        when(coverage.findById("nse")).thenReturn(Optional.of(cov));
        when(client.fetchWindow(eq(LocalDate.of(2026, 1, 1)), any())).thenReturn(ok());
        when(client.fetchWindow(eq(LocalDate.of(2025, 1, 1)), any())).thenReturn(failed());

        store.ensureCoverage(LocalDate.of(2024, 5, 3));

        verify(client, times(2)).fetchWindow(any(), any());
        assertThat(cov.getCoveredFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void alreadyCoveredFetchesNothing() {
        when(coverage.findById("nse")).thenReturn(Optional.of(covered(LocalDate.of(2024, 1, 1), TODAY)));
        store.ensureCoverage(LocalDate.of(2024, 5, 3));
        verify(client, never()).fetchWindow(any(), any());
    }

    @Test
    void refreshRecentReadsFromCoveredEndMinusAWeekAndMovesIt() {
        MarketEventCoverage cov = covered(LocalDate.of(2024, 1, 1), TODAY.minusDays(2));
        when(coverage.findById("nse")).thenReturn(Optional.of(cov));
        when(client.fetchWindow(any(), any())).thenReturn(ok());

        assertThat(store.refreshRecent()).isTrue();

        verify(client).fetchWindow(TODAY.minusDays(9), TODAY.plusDays(1));
        assertThat(cov.getCoveredTo()).isEqualTo(TODAY);
    }

    @Test
    void oneBadRowDoesNotFailTheWindow() {
        when(client.fetchWindow(any(), any())).thenReturn(new NseCorporateActionsClient.Result(
                NseCorporateActionsClient.Status.OK, java.util.Arrays.asList(null,
                        new Row("BRAVOW", null, "Bravo", "EQ", LocalDate.of(2026, 10, 29), "Bonus 1:2"))));

        assertThat(store.refreshWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketCorporateEvent>> saved = ArgumentCaptor.forClass(List.class);
        verify(events).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(MarketCorporateEvent::getSymbol).containsExactly("BRAVOW");
    }

    @Test
    void overlongSubjectDedupsAgainstItsStoredCutValue() {
        String longSubject = "Bonus 1:2 / " + "x".repeat(600);
        MarketCorporateEvent known = new MarketCorporateEvent();
        known.setSymbol("BRAVOW");
        known.setExDate(LocalDate.of(2026, 10, 29));
        known.setSubject(longSubject.substring(0, 500));
        when(events.findWindow(any(), any(), any())).thenReturn(List.of(known));
        when(client.fetchWindow(any(), any())).thenReturn(ok(
                new Row("BRAVOW", null, "Bravo", "EQ", LocalDate.of(2026, 10, 29), longSubject)));

        assertThat(store.refreshWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).isTrue();

        verify(events, never()).saveAll(any());
    }

    @Test
    void concurrentWriterFallsBackToRowByRowIgnoringDuplicates() {
        when(client.fetchWindow(any(), any())).thenReturn(ok(
                new Row("BRAVOW", null, "Bravo", "EQ", LocalDate.of(2026, 10, 29), "Bonus 1:2"),
                new Row("CHARLIE", null, "Charlie", "EQ", LocalDate.of(2026, 10, 29), "Bonus 1:1")));
        when(events.saveAll(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));
        when(events.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"))
                .thenAnswer(i -> i.getArgument(0));

        assertThat(store.refreshWindow(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).isTrue();

        verify(events, times(2)).save(any());
    }
}
