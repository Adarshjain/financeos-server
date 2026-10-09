package com.financeos.domain.report.breakdown;

import static com.financeos.domain.report.breakdown.BreakdownAssertions.assertReconciles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Counterparty rows of the net worth breakdown; totals come from {@link CounterpartyResponse#from}
 * over the same entries, and each breakdown reconciles exactly to the listed row value.
 */
class NetWorthLendingBreakdownTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("entryDate"), Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private LendingService lendingService;
    private LendingRepository lendingRepository;
    private NetWorthLendingBreakdown breakdown;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        lendingService = mock(LendingService.class);
        lendingRepository = mock(LendingRepository.class);
        breakdown = new NetWorthLendingBreakdown(lendingService, lendingRepository);
        when(lendingRepository.findByCounterparty_Id(any(UUID.class), any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void theyOweYouWhatYouLentOrPaidThemLessWhatTheyPaidYouOrYouBorrowed() {
        UUID id = UUID.randomUUID();
        CounterpartyResponse cp = counterparty(id, "Asha",
                entry(LendingDirection.lent, LendingKind.principal, "5000"),
                entry(LendingDirection.lent, LendingKind.settlement, "100"),
                entry(LendingDirection.borrowed, LendingKind.settlement, "2000"),
                entry(LendingDirection.borrowed, LendingKind.principal, "500"),
                entry(LendingDirection.lent, LendingKind.principal, "400"));

        RowBreakdownResponse r = breakdown.breakdown(id, 25).orElseThrow();

        assertEquals(List.of(
                step("add", "You lent / paid them (3)", "5500"),
                step("subtract", "They paid you / you borrowed (2)", "2500"),
                step("equals", "They owe you", "3000")), r.steps());
        assertEquals("net_worth", r.datasource());
        assertEquals(id.toString(), r.rowId());
        assertEquals("Asha", r.title());
        assertEquals("Asset", r.subtitle());
        assertEquals("Lending", r.kindLabel());
        assertEquals("They owe you", r.totalLabel());
        assertEquals(new BigDecimal("3000"), r.total());
        assertEquals(TODAY, r.asOf());
        assertEquals(List.of(), r.notes());
        BreakdownSectionData section = r.sections().get(0);
        assertEquals("entries", section.key());
        assertEquals("Entries", section.label());
        assertNull(section.rowAction());
        verify(lendingRepository).findByCounterparty_Id(id, PageRequest.of(0, 25, NEWEST_FIRST));
        assertMatchesListedRow(cp, r);
    }

    @Test
    void youOweThemRunsTheChainTheOtherWay() {
        UUID id = UUID.randomUUID();
        CounterpartyResponse cp = counterparty(id, "Ravi",
                entry(LendingDirection.borrowed, LendingKind.principal, "1500"),
                entry(LendingDirection.lent, LendingKind.settlement, "300"));

        RowBreakdownResponse r = breakdown.breakdown(id, 25).orElseThrow();

        assertEquals(List.of(
                step("subtract", "You lent / paid them (1)", "300"),
                step("add", "They paid you / you borrowed (1)", "1500"),
                step("equals", "You owe them", "1200")), r.steps());
        assertEquals("Liability", r.subtitle());
        assertMatchesListedRow(cp, r);
    }

    @Test
    void oneSidedLedgerHasOnlyThatMovement() {
        UUID id = UUID.randomUUID();
        CounterpartyResponse cp = counterparty(id, "Only lent", entry(LendingDirection.lent, LendingKind.principal, "250"));

        RowBreakdownResponse r = breakdown.breakdown(id, 25).orElseThrow();

        assertEquals(List.of(step("add", "You lent / paid them (1)", "250"), step("equals", "They owe you", "250")), r.steps());
        assertMatchesListedRow(cp, r);
    }

    @Test
    void settledOrUnknownCounterpartiesHaveNoBreakdown() {
        UUID settled = UUID.randomUUID();
        counterparty(settled, "Settled",
                entry(LendingDirection.lent, LendingKind.principal, "100"),
                entry(LendingDirection.borrowed, LendingKind.settlement, "100"));
        UUID unknown = UUID.randomUUID();
        when(lendingService.findOwnedCounterparty(unknown)).thenReturn(Optional.empty());

        assertTrue(breakdown.breakdown(settled, 25).isEmpty());
        assertTrue(breakdown.breakdown(unknown, 25).isEmpty());
        assertTrue(breakdown.section(settled, "entries", 0, 25).isEmpty());
        assertTrue(breakdown.section(unknown, "entries", 0, 25).isEmpty());
    }

    @Test
    void entriesSectionPagesTheLedgerNewestFirst() {
        UUID id = UUID.randomUUID();
        counterparty(id, "Asha", entry(LendingDirection.lent, LendingKind.principal, "100"));
        Lending lent = entry(LendingDirection.lent, LendingKind.principal, "100");
        lent.setNotes("Dinner");
        Lending repaid = entry(LendingDirection.borrowed, LendingKind.settlement, "40");
        when(lendingRepository.findByCounterparty_Id(id, PageRequest.of(1, 2, NEWEST_FIRST)))
                .thenReturn(new PageImpl<>(List.of(lent, repaid), PageRequest.of(1, 2, NEWEST_FIRST), 5));

        TableData table = (TableData) breakdown.section(id, "entries", 1, 2).orElseThrow();

        assertEquals(List.of("date", "direction", "kind", "amount", "notes"),
                table.columns().stream().map(TableData.Column::key).toList());
        assertEquals(List.of(entryRow(lent, "Lent", "Principal"), entryRow(repaid, "Borrowed", "Settlement")), table.rows());
        assertEquals(new TableData.Page(1, 2, 5, 3), table.page());
    }

    @Test
    void unknownSectionIsNotFound() {
        UUID id = UUID.randomUUID();
        counterparty(id, "Asha", entry(LendingDirection.lent, LendingKind.principal, "100"));

        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(id, "nope", 0, 25));
    }

    // ------------------------------------------------------------------ fixtures

    private void assertMatchesListedRow(CounterpartyResponse cp, RowBreakdownResponse r) {
        AccountService accounts = mock(AccountService.class);
        when(accounts.getAllAccounts()).thenReturn(List.of());
        LoanService loans = mock(LoanService.class);
        when(loans.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        LendingService lendings = mock(LendingService.class);
        when(lendings.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of(cp)));
        Map<String, Object> row = new NetWorthDatasource(accounts, loans, lendings).rows().get(0);

        assertEquals(row.get("value"), r.total());
        assertEquals("asset".equals(row.get("side")) ? "Asset" : "Liability", r.subtitle());
        assertReconciles(r);
    }

    /** The counterparty as LendingService lists it: totals accumulated from its entries. */
    private CounterpartyResponse counterparty(UUID id, String name, Lending... entries) {
        BigDecimal lent = BigDecimal.ZERO;
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal toYou = BigDecimal.ZERO;
        BigDecimal byYou = BigDecimal.ZERO;
        for (Lending l : entries) {
            boolean settlement = l.getKind() == LendingKind.settlement;
            if (l.getDirection() == LendingDirection.lent) {
                if (settlement) byYou = byYou.add(l.getAmount());
                else lent = lent.add(l.getAmount());
            } else if (settlement) {
                toYou = toYou.add(l.getAmount());
            } else {
                borrowed = borrowed.add(l.getAmount());
            }
        }
        Counterparty entity = new Counterparty();
        entity.setId(id);
        entity.setName(name);
        CounterpartyResponse cp = CounterpartyResponse.from(entity, lent, borrowed, toYou, byYou, entries.length);
        when(lendingService.findOwnedCounterparty(id)).thenReturn(Optional.of(cp));
        when(lendingRepository.findByCounterparty_Id(id)).thenReturn(new ArrayList<>(List.of(entries)));
        return cp;
    }

    private static Lending entry(LendingDirection direction, LendingKind kind, String amount) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setDirection(direction);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(TODAY.minusDays(10));
        return l;
    }

    private static Map<String, Object> entryRow(Lending l, String direction, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.getId().toString());
        row.put("date", l.getEntryDate());
        row.put("direction", direction);
        row.put("kind", kind);
        row.put("amount", l.getAmount());
        row.put("notes", l.getNotes());
        return row;
    }

    private static BreakdownStep step(String op, String label, String amount) {
        return new BreakdownStep(op, label, null, new BigDecimal(amount), "currency");
    }
}
