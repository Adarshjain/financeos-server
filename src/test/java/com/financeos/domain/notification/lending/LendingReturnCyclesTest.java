package com.financeos.domain.notification.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** dueObligations considers only the open cycle's return dates. */
class LendingReturnCyclesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final Counterparty person = new Counterparty();
    private final List<Lending> ledger = new ArrayList<>();

    LendingReturnCyclesTest() {
        person.setId(UUID.randomUUID());
        person.setName("Asha");
    }

    private Lending entry(LendingDirection dir, String amount, LocalDate date, LocalDate expected) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setCounterparty(person);
        l.setDirection(dir);
        l.setKind(LendingKind.principal);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(date);
        l.setExpectedReturnDate(expected);
        ledger.add(l);
        return l;
    }

    @Test
    void returnDateFromASettledCycleIsIgnoredWhenALaterCycleIsOpen() {
        entry(LendingDirection.lent, "100", TODAY.minusDays(90), TODAY.minusDays(60));
        entry(LendingDirection.borrowed, "100", TODAY.minusDays(80), null);
        entry(LendingDirection.lent, "40", TODAY.minusDays(10), null);

        assertTrue(LendingReturnNotificationService.dueObligations(ledger, TODAY).isEmpty());
    }

    @Test
    void returnDateInTheOpenCycleIsTheSubject() {
        entry(LendingDirection.lent, "100", TODAY.minusDays(90), TODAY.minusDays(60));
        entry(LendingDirection.borrowed, "100", TODAY.minusDays(80), null);
        Lending open = entry(LendingDirection.lent, "40", TODAY.minusDays(10), TODAY.minusDays(1));

        var due = LendingReturnNotificationService.dueObligations(ledger, TODAY);

        assertEquals(1, due.size());
        assertEquals(open, due.get(0).subject());
        assertEquals(new BigDecimal("40"), due.get(0).outstanding());
    }

    @Test
    void squarePersonIsNotAnObligationEvenWithDatedEntries() {
        entry(LendingDirection.lent, "100", TODAY.minusDays(90), TODAY.minusDays(60));
        entry(LendingDirection.borrowed, "100", TODAY.minusDays(80), null);

        assertTrue(LendingReturnNotificationService.dueObligations(ledger, TODAY).isEmpty());
    }
}
