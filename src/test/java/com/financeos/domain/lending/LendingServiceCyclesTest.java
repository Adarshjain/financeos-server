package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.CounterpartyLedgerResponse;
import com.financeos.api.lending.dto.LendingCycleResponse;
import com.financeos.api.lending.dto.LedgerEntryResponse;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/** Ledger cycle selector and cycle summaries; expected return dates ignore settled cycles. */
class LendingServiceCyclesTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final PageRequest ALL = PageRequest.of(0, 50);

    private CounterpartyRepository counterpartyRepository;
    private LendingRepository lendingRepository;
    private LendingService service;
    private int seq;

    @BeforeEach
    void setUp() {
        counterpartyRepository = mock(CounterpartyRepository.class);
        lendingRepository = mock(LendingRepository.class);
        service = new LendingService(counterpartyRepository, lendingRepository, mock(UserRepository.class),
                mock(TransactionReferenceValidator.class), mock(TransactionRepository.class),
                mock(TransactionLinkRepository.class));
        UserContext.setCurrentUserId(USER_ID);
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 10, 21).atTime(12, 0).atZone(IST).toInstant(), IST));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Counterparty person(String name) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        User u = new User();
        u.setId(USER_ID);
        cp.setUser(u);
        when(counterpartyRepository.findById(cp.getId())).thenReturn(Optional.of(cp));
        return cp;
    }

    private Lending entry(Counterparty cp, LendingDirection dir, String amount, String date, String expected) {
        Lending l = new Lending();
        l.setId(new UUID(0, ++seq));
        l.setCounterparty(cp);
        l.setDirection(dir);
        l.setKind(LendingKind.principal);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(LocalDate.parse(date));
        l.setExpectedReturnDate(expected == null ? null : LocalDate.parse(expected));
        l.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seq));
        return l;
    }

    private Lending lent(Counterparty cp, String amount, String date, String expected) {
        return entry(cp, LendingDirection.lent, amount, date, expected);
    }

    private Lending borrowed(Counterparty cp, String amount, String date) {
        return entry(cp, LendingDirection.borrowed, amount, date, null);
    }

    private void ledger(Counterparty cp, Lending... entries) {
        when(lendingRepository.findByCounterpartyIdWithRefs(cp.getId())).thenReturn(List.of(entries));
        when(lendingRepository.findByCounterparty_Id(cp.getId())).thenReturn(List.of(entries));
    }

    /** Two settled cycles then an open one (balance 150). */
    private Counterparty threeCycles() {
        Counterparty cp = person("Asha");
        ledger(cp,
                lent(cp, "500", "2026-01-01", null),       // c1
                borrowed(cp, "500", "2026-01-10"),         // c1 closes
                lent(cp, "300", "2026-02-01", null),       // c2
                borrowed(cp, "300", "2026-02-05"),         // c2 closes
                lent(cp, "200", "2026-03-01", null),       // c3
                borrowed(cp, "50", "2026-03-02"));         // c3 open, 150
        return cp;
    }

    private static List<Integer> cycleOf(CounterpartyLedgerResponse r) {
        return r.entries().getContent().stream().map(LedgerEntryResponse::cycle).toList();
    }

    @Test
    void absentAndAllReturnEveryEntryWithNoSelectedCycle() {
        Counterparty cp = threeCycles();

        for (String sel : new String[]{null, "all"}) {
            CounterpartyLedgerResponse r = service.getLedger(cp.getId(), sel, ALL);
            assertNull(r.selectedCycle());
            assertEquals(6, r.entries().getTotalElements());
            assertEquals(List.of(3, 3, 2, 2, 1, 1), cycleOf(r));
            assertEquals(new BigDecimal("1000"), r.totals().lent());
            assertEquals(new BigDecimal("850"), r.totals().received());
            assertEquals(new BigDecimal("150"), r.totals().closing());
        }
    }

    @Test
    void cyclesListIsNewestFirstWithBoundsFlagsCountsAndSums() {
        Counterparty cp = threeCycles();

        List<LendingCycleResponse> cycles = service.getLedger(cp.getId(), null, ALL).cycles();

        assertEquals(List.of(3, 2, 1), cycles.stream().map(LendingCycleResponse::number).toList());
        LendingCycleResponse open = cycles.get(0);
        assertEquals(LocalDate.of(2026, 3, 1), open.startDate());
        assertEquals(LocalDate.of(2026, 3, 2), open.endDate());
        assertEquals(false, open.settled());
        assertEquals(2, open.entryCount());
        assertEquals(new BigDecimal("200"), open.lent());
        assertEquals(new BigDecimal("50"), open.received());
        assertEquals(new BigDecimal("150"), open.closing());
        LendingCycleResponse first = cycles.get(2);
        assertEquals(LocalDate.of(2026, 1, 1), first.startDate());
        assertEquals(LocalDate.of(2026, 1, 10), first.endDate());
        assertTrue(first.settled());
        assertEquals(2, first.entryCount());
        assertEquals(new BigDecimal("500"), first.lent());
        assertEquals(new BigDecimal("500"), first.received());
        assertEquals(0, first.closing().signum());
    }

    @Test
    void latestSelectsTheOpenCycleAndFiltersEntriesAndTotals() {
        Counterparty cp = threeCycles();

        CounterpartyLedgerResponse r = service.getLedger(cp.getId(), "latest", ALL);

        assertEquals(3, r.selectedCycle());
        assertEquals(2, r.entries().getTotalElements());
        assertEquals(List.of(3, 3), cycleOf(r));
        assertEquals(List.of(new BigDecimal("150"), new BigDecimal("200")),
                r.entries().getContent().stream().map(LedgerEntryResponse::runningBalance).toList());
        assertEquals(new BigDecimal("200"), r.totals().lent());
        assertEquals(new BigDecimal("50"), r.totals().received());
        assertEquals(new BigDecimal("150"), r.totals().closing());
        assertEquals(3, r.cycles().size());
    }

    @Test
    void latestIsTheLastSettledCycleWhenSquare() {
        Counterparty cp = person("Asha");
        ledger(cp, lent(cp, "100", "2026-01-01", null), borrowed(cp, "100", "2026-01-02"),
                lent(cp, "40", "2026-02-01", null), borrowed(cp, "40", "2026-02-02"));

        CounterpartyLedgerResponse r = service.getLedger(cp.getId(), "latest", ALL);

        assertEquals(2, r.selectedCycle());
        assertEquals(List.of(2, 2), cycleOf(r));
        assertTrue(r.cycles().get(0).settled());
    }

    @Test
    void numberSelectsThatCycleAndKeepsEveryCycleListed() {
        Counterparty cp = threeCycles();

        CounterpartyLedgerResponse r = service.getLedger(cp.getId(), "2", ALL);

        assertEquals(2, r.selectedCycle());
        assertEquals(List.of(2, 2), cycleOf(r));
        assertEquals(new BigDecimal("300"), r.totals().lent());
        assertEquals(new BigDecimal("300"), r.totals().received());
        assertEquals(0, r.totals().closing().signum());
        assertEquals(3, r.cycles().size());
    }

    @Test
    void numberBeyondTheLastCycleFallsBackToLatest() {
        Counterparty cp = threeCycles();

        CounterpartyLedgerResponse r = service.getLedger(cp.getId(), "9", ALL);

        assertEquals(3, r.selectedCycle());
        assertEquals(List.of(3, 3), cycleOf(r));
    }

    @Test
    void invalidSelectorsAreRejectedAsValidationErrors() {
        Counterparty cp = threeCycles();

        for (String bad : new String[]{"0", "-1", "abc", "", "ALL", "1.5"}) {
            assertThrows(ValidationException.class, () -> service.getLedger(cp.getId(), bad, ALL), bad);
        }
    }

    @Test
    void pagingWorksInsideTheSelectedCycle() {
        Counterparty cp = person("Asha");
        ledger(cp, lent(cp, "10", "2026-01-01", null), borrowed(cp, "10", "2026-01-02"),
                lent(cp, "5", "2026-02-01", null), lent(cp, "5", "2026-02-02", null), lent(cp, "5", "2026-02-03", null));

        CounterpartyLedgerResponse p1 = service.getLedger(cp.getId(), "latest", PageRequest.of(1, 2));

        assertEquals(3, p1.entries().getTotalElements());
        assertEquals(1, p1.entries().getContent().size());
        assertEquals(LocalDate.of(2026, 2, 1), p1.entries().getContent().get(0).entryDate());
        assertEquals(new BigDecimal("15"), p1.totals().lent());
    }

    @Test
    void personWithoutEntriesHasEmptyPageAndNoCycles() {
        Counterparty cp = person("Asha");
        ledger(cp);

        for (String sel : new String[]{null, "all", "latest", "3"}) {
            CounterpartyLedgerResponse r = service.getLedger(cp.getId(), sel, ALL);
            assertNull(r.selectedCycle());
            assertEquals(0, r.entries().getTotalElements());
            assertTrue(r.cycles().isEmpty());
            assertEquals(0, r.totals().closing().signum());
        }
    }

    @Test
    void twoArgumentOverloadReturnsEveryEntry() {
        Counterparty cp = threeCycles();

        CounterpartyLedgerResponse r = service.getLedger(cp.getId(), ALL);

        assertNull(r.selectedCycle());
        assertEquals(6, r.entries().getTotalElements());
    }

    // --- expected return dates ignore settled cycles ---

    @Test
    void expectedReturnDateOfASettledCycleIsIgnoredWhenALaterCycleIsOpen() {
        Counterparty cp = person("Asha");
        ledger(cp, lent(cp, "100", "2026-01-01", "2026-02-01"), borrowed(cp, "100", "2026-01-15"),
                lent(cp, "40", "2026-03-01", null));

        assertNull(service.getCounterparty(cp.getId()).expectedReturnDate());
    }

    @Test
    void expectedReturnDateInTheOpenCycleIsPicked() {
        Counterparty cp = person("Asha");
        ledger(cp, lent(cp, "100", "2026-01-01", "2026-02-01"), borrowed(cp, "100", "2026-01-15"),
                lent(cp, "40", "2026-03-01", "2026-04-01"));

        assertEquals(LocalDate.of(2026, 4, 1), service.getCounterparty(cp.getId()).expectedReturnDate());
    }

    @Test
    void upcomingObligationsIgnoreDatesFromSettledCycles() {
        Counterparty stale = person("Asha");
        Counterparty live = person("Bala");
        Counterparty square = person("Chirag");
        List<Lending> all = List.of(
                lent(stale, "100", "2026-01-01", "2026-02-01"), borrowed(stale, "100", "2026-01-15"),
                lent(stale, "40", "2026-03-01", null),
                lent(live, "100", "2026-01-01", "2026-02-01"), borrowed(live, "100", "2026-01-15"),
                lent(live, "40", "2026-03-01", "2026-11-01"),
                lent(square, "10", "2026-01-01", "2026-02-01"), borrowed(square, "10", "2026-01-02"));
        when(counterpartyRepository.findAll()).thenReturn(List.of(stale, live, square));
        when(lendingRepository.findByCounterparty_IdIn(any())).thenReturn(all);

        List<ObligationItemDto> items = service.getUpcomingLendingObligations(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));

        assertEquals(1, items.size());
        assertEquals("Bala", items.get(0).counterpartyName());
        assertEquals(LocalDate.of(2026, 11, 1), items.get(0).date());
        assertEquals(new BigDecimal("40.00"), items.get(0).amount());
    }
}
