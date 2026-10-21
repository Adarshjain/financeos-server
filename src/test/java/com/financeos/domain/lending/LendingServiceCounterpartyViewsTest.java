package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.CounterpartyDirection;
import com.financeos.api.lending.dto.CounterpartyLedgerResponse;
import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.lending.dto.CounterpartySummaryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Last-entry / expected-return fields, direction filter, summary, ledger running balance. */
class LendingServiceCounterpartyViewsTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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

    private Counterparty person(String name, UUID owner) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        User u = new User();
        u.setId(owner);
        cp.setUser(u);
        when(counterpartyRepository.findById(cp.getId())).thenReturn(Optional.of(cp));
        return cp;
    }

    private Lending entry(Counterparty cp, LendingDirection dir, LendingKind kind, String amount, String entryDate, String expected) {
        Lending l = new Lending();
        l.setId(new UUID(0, ++seq));
        l.setCounterparty(cp);
        l.setDirection(dir);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(LocalDate.parse(entryDate));
        l.setExpectedReturnDate(expected == null ? null : LocalDate.parse(expected));
        l.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seq));
        return l;
    }

    private Lending lent(Counterparty cp, String amount, String date, String expected) {
        return entry(cp, LendingDirection.lent, LendingKind.principal, amount, date, expected);
    }

    private Lending borrowed(Counterparty cp, String amount, String date, String expected) {
        return entry(cp, LendingDirection.borrowed, LendingKind.principal, amount, date, expected);
    }

    private void ledger(Counterparty cp, Lending... entries) {
        when(lendingRepository.findByCounterparty_Id(cp.getId())).thenReturn(List.of(entries));
        when(lendingRepository.findByCounterpartyIdWithRefs(cp.getId())).thenReturn(List.of(entries));
    }

    @Test
    void lastEntryIsTheLatestByDateThenCreation() {
        Counterparty cp = person("Asha", USER_ID);
        ledger(cp, lent(cp, "100", "2026-03-01", null),
                borrowed(cp, "30", "2026-03-05", null),
                lent(cp, "70", "2026-03-05", null),     // same date, created later: the last one
                lent(cp, "5", "2026-02-01", null));

        CounterpartyResponse r = service.getCounterparty(cp.getId());

        assertEquals(LocalDate.of(2026, 3, 5), r.lastEntryDate());
        assertEquals(LendingDirection.lent, r.lastEntryType());
        assertEquals(new BigDecimal("70"), r.lastEntryAmount());
        assertEquals(LendingKind.principal, r.lastEntryKind());
    }

    @Test
    void lastEntryKindIsSettlementWhenTheLatestEntryIsARepayment() {
        Counterparty cp = person("Asha", USER_ID);
        ledger(cp, lent(cp, "100", "2026-03-01", null),
                entry(cp, LendingDirection.borrowed, LendingKind.settlement, "40", "2026-03-09", null));

        CounterpartyResponse r = service.getCounterparty(cp.getId());

        assertEquals(LendingKind.settlement, r.lastEntryKind());
        assertEquals(LendingDirection.borrowed, r.lastEntryType());
    }

    @Test
    void expectedReturnIsEarliestPrincipalDateInTheOutstandingDirection() {
        Counterparty cp = person("Asha", USER_ID);
        ledger(cp, lent(cp, "100", "2026-03-01", "2026-12-01"),
                lent(cp, "100", "2026-03-02", "2026-11-15"),
                borrowed(cp, "10", "2026-03-03", "2026-01-01"),          // wrong direction: ignored
                entry(cp, LendingDirection.lent, LendingKind.settlement, "5", "2026-03-04", "2026-01-02")); // settlement: ignored

        assertEquals(LocalDate.of(2026, 11, 15), service.getCounterparty(cp.getId()).expectedReturnDate());
    }

    @Test
    void expectedReturnIsNullWhenSettledOrUnset() {
        Counterparty settled = person("Bala", USER_ID);
        ledger(settled, lent(settled, "100", "2026-03-01", "2026-12-01"), borrowed(settled, "100", "2026-03-02", null));
        Counterparty unset = person("Chirag", USER_ID);
        ledger(unset, lent(unset, "100", "2026-03-01", null));
        Counterparty empty = person("Dev", USER_ID);
        ledger(empty);

        assertNull(service.getCounterparty(settled.getId()).expectedReturnDate());
        assertNull(service.getCounterparty(unset.getId()).expectedReturnDate());
        assertNull(service.getCounterparty(empty.getId()).expectedReturnDate());
        assertNull(service.getCounterparty(empty.getId()).lastEntryDate());
        assertNull(service.getCounterparty(empty.getId()).lastEntryKind());
    }

    @Test
    void getCounterpartyIs404ForMissingAndForeign() {
        Counterparty foreign = person("Zed", UUID.randomUUID());
        assertThrows(ResourceNotFoundException.class, () -> service.getCounterparty(foreign.getId()));
        assertThrows(ResourceNotFoundException.class, () -> service.getCounterparty(UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class, () -> service.getLedger(foreign.getId(), PageRequest.of(0, 10)));
        assertThrows(ResourceNotFoundException.class, () -> service.getLedger(UUID.randomUUID(), PageRequest.of(0, 10)));
    }

    @Test
    void directionFilterSplitsBySignAndReportsFilteredTotals() {
        List<Object[]> rows = new ArrayList<>();
        String[][] people = {{"Asha", "500"}, {"Bala", "-200"}, {"Chirag", "0"}, {"Dev", "300"}, {"Esha", "-50"}};
        for (String[] p : people) {
            Counterparty cp = person(p[0], USER_ID);
            ledger(cp);
            rows.add(new Object[]{cp.getId(), p[0], new BigDecimal(p[1])});
        }
        when(counterpartyRepository.findAllById(org.mockito.ArgumentMatchers.anyIterable()))
                .thenAnswer(inv -> {
                    List<Counterparty> found = new ArrayList<>();
                    for (Object id : (Iterable<?>) inv.getArgument(0)) {
                        found.add(counterpartyRepository.findById((UUID) id).orElseThrow());
                    }
                    return found;
                });
        when(counterpartyRepository.findNetPositions(USER_ID)).thenReturn(rows);

        var owed = service.getCounterparties(null, false, CounterpartyDirection.OWED_TO_YOU, PageRequest.of(0, 1, Sort.by("name")));
        assertEquals(2, owed.getTotalElements());
        assertEquals(List.of("Asha"), owed.getContent().stream().map(CounterpartyResponse::name).toList());
        var owedPage2 = service.getCounterparties(null, false, CounterpartyDirection.OWED_TO_YOU, PageRequest.of(1, 1, Sort.by("name")));
        assertEquals(List.of("Dev"), owedPage2.getContent().stream().map(CounterpartyResponse::name).toList());

        var owe = service.getCounterparties(null, false, CounterpartyDirection.YOU_OWE, PageRequest.of(0, 50, Sort.by("net")));
        assertEquals(List.of("Bala", "Esha"), owe.getContent().stream().map(CounterpartyResponse::name).toList());

        var settled = service.getCounterparties(null, false, CounterpartyDirection.SETTLED, PageRequest.of(0, 50, Sort.by("name")));
        assertEquals(List.of("Chirag"), settled.getContent().stream().map(CounterpartyResponse::name).toList());

        var qAndDirection = service.getCounterparties("e", false, CounterpartyDirection.YOU_OWE, PageRequest.of(0, 50, Sort.by("name")));
        assertEquals(List.of("Esha"), qAndDirection.getContent().stream().map(CounterpartyResponse::name).toList());
        var qNarrow = service.getCounterparties("es", false, CounterpartyDirection.YOU_OWE, PageRequest.of(0, 50, Sort.by("name")));
        assertEquals(List.of("Esha"), qNarrow.getContent().stream().map(CounterpartyResponse::name).toList());
    }

    @Test
    void summaryCountsExpectedDatesUpToTheLastDayOfTheMonthOnly() {
        Counterparty lastDay = person("OnLastDay", USER_ID);          // owes you 100, expected 31 Oct: counted
        Counterparty nextMonth = person("NextMonth", USER_ID);        // owes you 200, expected 1 Nov: not counted
        Counterparty overdue = person("Overdue", USER_ID);            // owes you 50, expected in the past: counted
        Counterparty iOweLast = person("IOweLast", USER_ID);          // you owe 300, 31 Oct: counted
        Counterparty iOweNext = person("IOweNext", USER_ID);          // you owe 400, 1 Nov: not counted
        Counterparty iOweEarly = person("IOweEarly", USER_ID);        // you owe 20, 5 Oct
        Counterparty settled = person("Settled", USER_ID);
        when(lendingRepository.findAllWithRefs()).thenReturn(List.of(
                lent(lastDay, "100", "2026-09-01", "2026-10-31"),
                lent(nextMonth, "200", "2026-09-01", "2026-11-01"),
                lent(overdue, "80", "2026-08-01", "2026-09-10"),
                entry(overdue, LendingDirection.borrowed, LendingKind.settlement, "30", "2026-08-05", null),
                borrowed(iOweLast, "300", "2026-09-01", "2026-10-31"),
                borrowed(iOweNext, "400", "2026-09-01", "2026-11-01"),
                borrowed(iOweEarly, "20", "2026-09-01", "2026-10-05"),
                lent(settled, "10", "2026-09-01", "2026-10-01"),
                borrowed(settled, "10", "2026-09-02", null)));

        CounterpartySummaryResponse s = service.getCounterpartySummary();

        assertEquals(new BigDecimal("350"), s.owedToYou());
        assertEquals(3, s.owedToYouCount());
        assertEquals(new BigDecimal("720"), s.youOwe());
        assertEquals(3, s.youOweCount());
        assertEquals(new BigDecimal("150"), s.expectedBack().amount());
        assertEquals(2, s.expectedBack().count());
        assertEquals("Overdue", s.expectedBack().nextName());
        assertEquals(LocalDate.of(2026, 9, 10), s.expectedBack().nextDate());
        assertEquals(new BigDecimal("320"), s.dueThisMonth().amount());
        assertEquals(2, s.dueThisMonth().count());
        assertEquals("IOweEarly", s.dueThisMonth().nextName());
        assertEquals(LocalDate.of(2026, 10, 5), s.dueThisMonth().nextDate());
    }

    @Test
    void summaryWithNothingDueHasZeroBucketsAndNullNext() {
        Counterparty p = person("Later", USER_ID);
        when(lendingRepository.findAllWithRefs()).thenReturn(List.of(lent(p, "100", "2026-09-01", "2026-11-01")));

        CounterpartySummaryResponse s = service.getCounterpartySummary();

        assertEquals(new BigDecimal("100"), s.owedToYou());
        assertEquals(0, s.expectedBack().count());
        assertEquals(BigDecimal.ZERO, s.expectedBack().amount());
        assertNull(s.expectedBack().nextName());
        assertNull(s.expectedBack().nextDate());
        assertEquals(0, s.dueThisMonth().count());
        assertNull(s.dueThisMonth().nextDate());
    }

    @Test
    void ledgerRunsOldestFirstAcrossPagesWithSameDateEntriesStable() {
        Counterparty cp = person("Asha", USER_ID);
        Lending e1 = lent(cp, "1000", "2026-03-01", null);        // +1000 -> 1000
        Lending e2 = borrowed(cp, "300", "2026-03-05", null);     // -300  -> 700
        Lending e3 = lent(cp, "200", "2026-03-05", null);         // +200  -> 900 (same date, created after e2)
        Lending e4 = borrowed(cp, "900", "2026-03-09", null);     // -900  -> 0
        Lending e5 = lent(cp, "50", "2026-03-10", null);          // +50   -> 50
        ledger(cp, e3, e5, e1, e4, e2);                           // returned unordered

        CounterpartyLedgerResponse p0 = service.getLedger(cp.getId(), PageRequest.of(0, 2));
        CounterpartyLedgerResponse p1 = service.getLedger(cp.getId(), PageRequest.of(1, 2));
        CounterpartyLedgerResponse p2 = service.getLedger(cp.getId(), PageRequest.of(2, 2));

        assertEquals(5, p0.entries().getTotalElements());
        assertEquals(List.of(e5.getId(), e4.getId()), p0.entries().getContent().stream().map(x -> x.id()).toList());
        assertEquals(List.of(new BigDecimal("50"), BigDecimal.ZERO),
                p0.entries().getContent().stream().map(x -> x.runningBalance()).toList());
        assertEquals(List.of(e3.getId(), e2.getId()), p1.entries().getContent().stream().map(x -> x.id()).toList());
        assertEquals(List.of(new BigDecimal("900"), new BigDecimal("700")),
                p1.entries().getContent().stream().map(x -> x.runningBalance()).toList());
        assertEquals(List.of(e1.getId()), p2.entries().getContent().stream().map(x -> x.id()).toList());
        assertEquals(new BigDecimal("1000"), p2.entries().getContent().get(0).runningBalance());
        assertEquals("Asha", p2.entries().getContent().get(0).counterpartyName());

        // totals cover every entry regardless of the page
        assertEquals(new BigDecimal("1250"), p1.totals().lent());
        assertEquals(new BigDecimal("1200"), p1.totals().received());
        assertEquals(new BigDecimal("50"), p1.totals().closing());
    }

    @Test
    void settlementEntriesMoveTheBalanceByTheirDirection() {
        Counterparty cp = person("Asha", USER_ID);
        Lending a = lent(cp, "500", "2026-03-01", null);
        Lending b = entry(cp, LendingDirection.borrowed, LendingKind.settlement, "200", "2026-03-02", null);
        ledger(cp, a, b);

        var content = service.getLedger(cp.getId(), PageRequest.of(0, 10)).entries().getContent();

        assertEquals(new BigDecimal("300"), content.get(0).runningBalance());
        assertEquals(new BigDecimal("500"), content.get(1).runningBalance());
    }

    @Test
    void allMatchSuggestionsOmitsPeopleWithoutCandidates() {
        Counterparty a = person("Asha", USER_ID);
        Counterparty b = person("Bala", USER_ID);
        ledger(a);
        ledger(b);
        when(counterpartyRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))).thenReturn(List.of(a, b));

        assertEquals(List.of(), service.getAllMatchSuggestions());
    }
}
