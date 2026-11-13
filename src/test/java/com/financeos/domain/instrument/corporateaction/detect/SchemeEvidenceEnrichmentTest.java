package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Document;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Listed;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Notice;
import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceEnrichment.Target;
import com.financeos.domain.instrument.price.PriceHistoryClient;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

class SchemeEvidenceEnrichmentTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 20);
    private static final LocalDate EX = LocalDate.of(2025, 3, 10);

    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
    private final InstrumentPriceRepository prices = mock(InstrumentPriceRepository.class);
    private final SchemeEvidenceRepository evidence = mock(SchemeEvidenceRepository.class);
    private final SchemeEvidenceChildRepository children = mock(SchemeEvidenceChildRepository.class);
    private final NseAnnouncementsClient announcements = mock(NseAnnouncementsClient.class);
    private final NseListingsClient listings = mock(NseListingsClient.class);
    private final NseNameChangesClient nameChanges = mock(NseNameChangesClient.class);
    private final PriceHistoryClient priceHistory = mock(PriceHistoryClient.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SchemeEvidenceEnrichment service = new SchemeEvidenceEnrichment(suggestions, transactions, prices, evidence,
            children, announcements, listings, nameChanges, priceHistory, tx);
    private final List<SchemeEvidence> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(java.time.Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        doAnswer(i -> {
            ((Consumer<Object>) i.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        when(evidence.findScheme(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(evidence.saveAndFlush(any(SchemeEvidence.class))).thenAnswer(i -> {
            SchemeEvidence e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            saved.add(e);
            return e;
        });
        when(evidence.save(any(SchemeEvidence.class))).thenAnswer(i -> {
            SchemeEvidence e = i.getArgument(0);
            saved.add(e);
            return e;
        });
        when(priceHistory.fetchYahoo(anyString(), any(), any()))
                .thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of()));
        when(nameChanges.nameChanges()).thenReturn(List.of());
        when(listings.listings()).thenReturn(List.of(
                new Listing("ACMEFOODS", "Acme Foods Limited", EX.plusDays(40), "INE000B01011"),
                new Listing("ACME", "Acme Industries Limited", EX.minusYears(20), "INE000A01011")));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static Target demerger(String symbol, LocalDate ex) {
        return new Target(SchemeNoticeReader.Kind.demerger, symbol, "INE000A01011", "Acme Industries Limited", "ACME.NS", ex, null);
    }

    private static SchemeEvidence row(boolean complete, Instant next) {
        SchemeEvidence e = new SchemeEvidence();
        e.setId(UUID.randomUUID());
        e.setComplete(complete);
        e.setNextCheckAt(next);
        return e;
    }

    private static Notice notice(String summary, String file) {
        return new Notice("ACME", LocalDate.of(2025, 3, 1), "Corporate Action", summary, file, "INE000A01011");
    }

    private static final String CHILD_SENTENCE = "Record date for the demerger of Acme Industries Limited: 1 (One) equity share of "
            + "Acme Foods Limited shall be issued for every 10 (Ten) equity shares held in Acme Industries Limited.";

    // ---- scheduling

    @Test
    void checksDailyUntil200DaysThenWeeklyUntil400ThenNever() {
        Instant now = Instant.parse("2025-03-20T10:00:00Z");
        Target t = demerger("ACME", EX);

        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, EX.plusDays(5), now)).isEqualTo(now.plusSeconds(86_400));
        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, EX.plusDays(199), now)).isEqualTo(now.plusSeconds(86_400));
        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, EX.plusDays(200), now)).isEqualTo(now.plusSeconds(7 * 86_400));
        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, EX.plusDays(399), now)).isEqualTo(now.plusSeconds(7 * 86_400));
        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, EX.plusDays(400), now)).isNull();
        assertThat(SchemeEvidenceEnrichment.nextCheck(t, true, EX.plusDays(5), now)).isNull();
    }

    @Test
    void aMergerIsCheckedFromItsLastTradeDate() {
        Instant now = Instant.parse("2025-03-20T10:00:00Z");
        Target t = new Target(SchemeNoticeReader.Kind.merger, "GONE", null, "Gone Limited", null, null, LocalDate.of(2025, 3, 1));

        assertThat(SchemeEvidenceEnrichment.nextCheck(t, false, TODAY, now)).isEqualTo(now.plusSeconds(86_400));
    }

    @Test
    void onlyMissingOrIncompleteAndDueEvidenceIsRead() {
        Instant now = Instant.parse("2025-03-20T10:00:00Z");
        Target none = demerger("NONE", EX);
        Target done = demerger("DONE", EX);
        Target later = demerger("LATER", EX);
        Target due = demerger("DUE", EX);
        when(evidence.findScheme("DONE", "demerger", EX)).thenReturn(Optional.of(row(true, null)));
        when(evidence.findScheme("LATER", "demerger", EX)).thenReturn(Optional.of(row(false, now.plusSeconds(3600))));
        when(evidence.findScheme("DUE", "demerger", EX)).thenReturn(Optional.of(row(false, now.minusSeconds(60))));

        assertThat(service.dueTargets(List.of(none, done, later, due), now)).containsExactly(none, due);
    }

    // ---- the reading

    @Test
    void aSingleChildWithNoPublishedCostGetsAPriceEstimateAndStaysOpen() {
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(notice(CHILD_SENTENCE, null)), false));
        when(priceHistory.fetchYahoo(eq("ACME.NS"), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.OK, List.of(
                new PriceHistoryClient.DailyClose(EX.minusDays(1), new BigDecimal("200.0000")),
                new PriceHistoryClient.DailyClose(EX, new BigDecimal("173.0000")))));

        Boolean complete = service.enrich(demerger("ACME", EX));

        assertThat(complete).isFalse();
        SchemeEvidence e = saved.get(saved.size() - 1);
        assertThat(e.getCostKind()).isEqualTo("ESTIMATE");
        assertThat(e.getParentCostPct()).isEqualByComparingTo("86.50");
        assertThat(e.getCostUrl()).isNull();
        assertThat(e.getMissing()).contains(SchemeNoticeReader.COST_NOT_PUBLISHED_YET);
        assertThat(e.getNextCheckAt()).isNotNull();
        ArgumentCaptor<SchemeEvidenceChild> child = ArgumentCaptor.forClass(SchemeEvidenceChild.class);
        verify(children).save(child.capture());
        assertThat(child.getValue().getChildSymbol()).isEqualTo("ACMEFOODS");
        assertThat(child.getValue().getRatioFrom()).isEqualTo(10);
        assertThat(child.getValue().getRatioTo()).isEqualTo(1);
        assertThat(child.getValue().getChildCostPct()).isEqualByComparingTo("13.50");
        verify(children).deleteForEvidence(any());
    }

    @Test
    void aCostSplitFromTheNoticeIsFinalAndSkipsThePriceEstimate() {
        String cost = " Percentage of cost of acquisition of equity shares: Acme Industries Limited (Demerged Company) 80.25% "
                + "Acme Foods Limited (Resulting Company) 19.75% Total 100%";
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(notice(CHILD_SENTENCE + cost, null)), false));

        Boolean complete = service.enrich(demerger("ACME", EX));

        assertThat(complete).isTrue();
        SchemeEvidence e = saved.get(saved.size() - 1);
        assertThat(e.getCostKind()).isEqualTo("NOTICE");
        assertThat(e.getParentCostPct()).isEqualByComparingTo("80.25");
        assertThat(e.isComplete()).isTrue();
        assertThat(e.getNextCheckAt()).isNull();
        verify(priceHistory, never()).fetchYahoo(anyString(), any(), any());
    }

    @Test
    void readsTheAttachedDocumentsAndBothSymbols() {
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(
                List.of(notice("Record date for the demerger", "https://nsearchives.example/corporate/ACME_rd.pdf")), false));
        when(announcements.fetchDocument("https://nsearchives.example/corporate/ACME_rd.pdf"))
                .thenReturn(new Document(true, CHILD_SENTENCE, false));

        service.enrich(demerger("ACMEOLD", EX));

        ArgumentCaptor<java.util.Set<String>> symbols = ArgumentCaptor.forClass(java.util.Set.class);
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(announcements).list(symbols.capture(), from.capture(), to.capture());
        assertThat(symbols.getValue()).containsExactlyInAnyOrder("ACMEOLD", "ACME"); // the event symbol and the current one (by ISIN)
        assertThat(from.getValue()).isEqualTo(EX.minusDays(730));
        assertThat(to.getValue()).isEqualTo(TODAY); // 200 days after the ex-date is still ahead
        ArgumentCaptor<SchemeEvidenceChild> child = ArgumentCaptor.forClass(SchemeEvidenceChild.class);
        verify(children).save(child.capture());
        assertThat(child.getValue().getRatioUrl()).isEqualTo("https://nsearchives.example/corporate/ACME_rd.pdf");
    }

    @Test
    void aMergerWindowIsAroundTheLastTrade() {
        LocalDate last = LocalDate.of(2025, 1, 15);
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(), false));

        service.enrich(new Target(SchemeNoticeReader.Kind.merger, "GONE", null, "Gone Limited", null, null, last));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(announcements).list(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(last.minusDays(400));
        assertThat(to.getValue()).isEqualTo(last.plusDays(60));
    }

    @Test
    void whenTheFeedFailsWithNothingTheRowIsKeptForANewTry() {
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(), true));

        Boolean out = service.enrich(demerger("ACME", EX));

        assertThat(out).isNull();
        SchemeEvidence e = saved.get(saved.size() - 1);
        assertThat(e.getMissing()).isEqualTo(SchemeNoticeReader.NOTICES_UNAVAILABLE);
        assertThat(e.getNextCheckAt()).isNotNull();
        verify(children, never()).save(any());
    }

    // ---- what is read

    @Test
    void gatherTakesDistinctPendingDemergersAndHeldStoppedStocks() {
        Instrument acme = new Instrument();
        acme.setId(UUID.randomUUID());
        acme.setSymbol("acme");
        acme.setName("Acme Industries Limited");
        acme.setIsin("INE000A01011");
        CorporateActionSuggestion a = suggestion(acme, EX);
        CorporateActionSuggestion b = suggestion(acme, EX); // another user's, same event
        when(suggestions.findAllPendingOfKind(SuggestionKind.demerger)).thenReturn(List.of(a, b));
        Instrument gone = new Instrument();
        gone.setId(UUID.randomUUID());
        gone.setSymbol("GONE");
        gone.setName("Gone Limited");
        when(transactions.findNotTradingInstrumentsTradedByAnyone()).thenReturn(List.of(gone));
        InstrumentPrice p = new InstrumentPrice();
        p.setInstrument(gone);
        p.setAsOf(LocalDate.of(2025, 1, 15));
        when(prices.findLatestFeedRows(anyCollection())).thenReturn(List.of(p));

        List<Target> targets = service.gather();

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0)).extracting(Target::kind, Target::symbol, Target::exDate)
                .containsExactly(SchemeNoticeReader.Kind.demerger, "ACME", EX);
        assertThat(targets.get(1)).extracting(Target::kind, Target::symbol, Target::lastTrade)
                .containsExactly(SchemeNoticeReader.Kind.merger, "GONE", LocalDate.of(2025, 1, 15));
    }

    @Test
    void aRunReadsAtMost25EventsAndSurvivesOneFailing() {
        List<CorporateActionSuggestion> pending = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Instrument ins = new Instrument();
            ins.setId(UUID.randomUUID());
            ins.setSymbol("S" + i);
            pending.add(suggestion(ins, EX));
        }
        when(suggestions.findAllPendingOfKind(SuggestionKind.demerger)).thenReturn(pending);
        when(transactions.findNotTradingInstrumentsTradedByAnyone()).thenReturn(List.of());
        when(announcements.list(any(), any(), any())).thenThrow(new IllegalStateException("boom"))
                .thenReturn(new Listed(List.of(), true));
        List<Integer> checkpoints = new ArrayList<>();

        SchemeEvidenceEnrichment.Summary summary = service.run(() -> checkpoints.add(1));

        assertThat(summary.due()).isEqualTo(30);
        verify(announcements, times(25)).list(any(), any(), any());
        assertThat(checkpoints).hasSize(25);
    }

    @Test
    void aRunWaitsWhileTheListingListIsNotLoaded() {
        when(listings.listings()).thenReturn(List.of());
        Instrument ins = new Instrument();
        ins.setId(UUID.randomUUID());
        ins.setSymbol("S1");
        when(suggestions.findAllPendingOfKind(SuggestionKind.demerger)).thenReturn(List.of(suggestion(ins, EX)));
        when(transactions.findNotTradingInstrumentsTradedByAnyone()).thenReturn(List.of());

        SchemeEvidenceEnrichment.Summary summary = service.run(() -> { });

        assertThat(summary.read()).isZero();
        verify(announcements, never()).list(any(), any(), any());
    }

    // ---- estimate

    @Test
    void theEstimateIsTheExDayCloseOverThePreviousCloseWithinOneAndNinetyNine() {
        assertThat(SchemeEvidenceEnrichment.pctOf(new BigDecimal("173"), new BigDecimal("200"))).isEqualByComparingTo("86.50");
        assertThat(SchemeEvidenceEnrichment.pctOf(new BigDecimal("1"), new BigDecimal("200"))).isEqualByComparingTo("1.00");
        assertThat(SchemeEvidenceEnrichment.pctOf(new BigDecimal("199.9"), new BigDecimal("200"))).isEqualByComparingTo("99.00");
        assertThat(SchemeEvidenceEnrichment.pctOf(new BigDecimal("210"), new BigDecimal("200"))).isEqualByComparingTo("99.00");
        assertThat(SchemeEvidenceEnrichment.pctOf(null, new BigDecimal("200"))).isNull();
        assertThat(SchemeEvidenceEnrichment.pctOf(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
    }

    @Test
    void noEstimateWhenThePriceFeedCannotAnswer() {
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(notice(CHILD_SENTENCE, null)), false));
        when(priceHistory.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.RATE_LIMITED, List.of()));

        service.enrich(demerger("ACME", EX));

        SchemeEvidence e = saved.get(saved.size() - 1);
        assertThat(e.getParentCostPct()).isNull();
        assertThat(e.getCostKind()).isNull();
        verify(priceHistory, atLeastOnce()).fetchYahoo(anyString(), any(), any());
    }

    @Test
    void aUserRunReadsOnlyThatUsersDueEventsAtMostTenAndSkipsNotYetDueOnes() {
        UUID user = UUID.randomUUID();
        List<CorporateActionSuggestion> pending = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            Instrument ins = new Instrument();
            ins.setId(UUID.randomUUID());
            ins.setSymbol("U" + i);
            pending.add(suggestion(ins, EX));
        }
        when(suggestions.findPendingUnresolved(user)).thenReturn(pending);
        when(transactions.findNotTradingInstrumentsTradedBy(user)).thenReturn(List.of());
        // U0 was read a moment ago and is not due again yet
        SchemeEvidence notDue = row(false, Instant.now().plusSeconds(3600));
        when(evidence.findScheme("U0", "demerger", EX)).thenReturn(Optional.of(notDue));
        when(announcements.list(any(), any(), any())).thenReturn(new Listed(List.of(), true));

        SchemeEvidenceEnrichment.Summary out = service.runForUser(user, () -> { });

        assertThat(out.due()).isEqualTo(13);
        verify(announcements, times(10)).list(any(), any(), any());
        verify(suggestions, never()).findAllPendingOfKind(any());
        verify(transactions, never()).findNotTradingInstrumentsTradedByAnyone();
    }

    private static CorporateActionSuggestion suggestion(Instrument instrument, LocalDate ex) {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(instrument);
        s.setKind(SuggestionKind.demerger);
        s.setExDate(ex);
        s.setStatus(SuggestionStatus.pending);
        return s;
    }
}
