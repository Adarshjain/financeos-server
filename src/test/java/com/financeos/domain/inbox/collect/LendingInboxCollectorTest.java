package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LendingInboxCollectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final LendingRepository repository = mock(LendingRepository.class);
    private final LendingInboxCollector collector = new LendingInboxCollector(repository);
    private final UUID userId = UUID.randomUUID();
    private final List<Lending> ledger = new ArrayList<>();
    private final Counterparty rahul = counterparty("Rahul");
    private final Counterparty priya = counterparty("Priya");

    @BeforeEach
    void setUp() {
        when(repository.findAllWithRefs()).thenReturn(ledger);
    }

    private static Counterparty counterparty(String name) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        return cp;
    }

    private Lending entry(Counterparty cp, LendingDirection direction, LendingKind kind, String amount, LocalDate entryDate, LocalDate expectedBack) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setCounterparty(cp);
        l.setDirection(direction);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(entryDate);
        l.setExpectedReturnDate(expectedBack);
        ledger.add(l);
        return l;
    }

    @Test
    void moneyLentAndDueWithinAWeekLandsOnTheLedgerShareSheet() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "10000", LocalDate.of(2026, 9, 1), TODAY.plusDays(3));
        entry(rahul, LendingDirection.borrowed, LendingKind.settlement, "4000", LocalDate.of(2026, 10, 1), null);

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        String href = "/loans/lendings/" + rahul.getId() + "?export=1";
        assertEquals("lending:" + rahul.getId(), row.key());
        assertEquals("lending", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Rahul owes you", row.title(), "the amount is its own field, never in the title");
        assertEquals("Due in 3 days · lent on 1 Sep", row.subtitle());
        assertEquals(href, row.href());
        assertEquals(new BigDecimal("6000"), row.amount(), "the net outstanding after settlements");
        assertEquals(TODAY.plusDays(3), row.date());
        assertEquals(List.of(InboxRows.open(href), InboxRows.snooze()), row.actions());
        assertEquals(InboxRefsResponse.ofCounterparty(rahul.getId()), row.refs());
    }

    @Test
    void moneyBorrowedReadsYouOweAndLinksToThePlainLedger() {
        entry(priya, LendingDirection.borrowed, LendingKind.principal, "2500", LocalDate.of(2026, 10, 5), TODAY);

        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        assertEquals("You owe Priya", row.title());
        assertEquals("Due today · borrowed on 5 Oct", row.subtitle());
        assertEquals("/loans/lendings/" + priya.getId(), row.href());
        assertEquals(new BigDecimal("2500"), row.amount());
        assertEquals("warning", row.severity());
    }

    @Test
    void overdueReturnIsCriticalAndNamesTheExpectedDate() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "10000", LocalDate.of(2026, 9, 1), TODAY.minusDays(2));

        InboxItemResponse row = collector.collect(userId, TODAY).get(0);

        assertEquals("critical", row.severity());
        assertEquals("Overdue by 2 days · expected back 18 Oct", row.subtitle());
    }

    @Test
    void returnsDueWithinSevenDaysShowAndLaterOnesDoNot() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "1000", LocalDate.of(2026, 10, 1), TODAY.plusDays(7));
        entry(priya, LendingDirection.lent, LendingKind.principal, "1000", LocalDate.of(2026, 10, 1), TODAY.plusDays(8));

        List<InboxItemResponse> rows = collector.collect(userId, TODAY);

        assertEquals(List.of("lending:" + rahul.getId()), rows.stream().map(InboxItemResponse::key).toList());
    }

    @Test
    void aSettledCounterpartyHasNothingDue() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "5000", LocalDate.of(2026, 9, 1), TODAY.minusDays(1));
        entry(rahul, LendingDirection.borrowed, LendingKind.settlement, "5000", LocalDate.of(2026, 10, 10), null);

        assertEquals(List.of(), collector.collect(userId, TODAY));
    }
}
