package com.financeos.domain.notification.emi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.domain.loan.Loan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** EMI push deep links: the inbox row while the inbox lists the installment, the loan page otherwise. */
class EmiMessagesTest {

    private Loan loan;
    private InstallmentDto installment;
    private String inbox;
    private String loanPage;

    @BeforeEach
    void setUp() {
        loan = new Loan();
        loan.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        loan.setName("Home loan");
        installment = new InstallmentDto(4, LocalDate.of(2026, 10, 23), new BigDecimal("100000"), new BigDecimal("25000"),
                new BigDecimal("1000"), new BigDecimal("24000"), new BigDecimal("76000"), "upcoming", null);
        inbox = "/inbox?item=emi:" + loan.getId() + ":4";
        loanPage = "/loans/" + loan.getId() + "?installment=4";
    }

    @Test
    void hrefIsTheInboxRowFromOverdueThroughSevenDaysAndTheLoanPageAfter() {
        assertEquals(inbox, EmiMessages.href(loan, installment, -30));
        assertEquals(inbox, EmiMessages.href(loan, installment, -1));
        assertEquals(inbox, EmiMessages.href(loan, installment, 0));
        assertEquals(inbox, EmiMessages.href(loan, installment, 7));
        assertEquals(loanPage, EmiMessages.href(loan, installment, 8));
        assertEquals(loanPage, EmiMessages.href(loan, installment, 14));
    }

    @Test
    void dueAndOverdueMessagesCarryTheDeepLinkForTheirDayCount() {
        assertEquals(inbox, EmiMessages.due(loan, installment, 12, 3).url());
        assertEquals(loanPage, EmiMessages.due(loan, installment, 12, 10).url());
        assertEquals(loanPage, EmiMessages.forKind("DUE_14", loan, installment, 12, 14).url());
        assertEquals(inbox, EmiMessages.forKind("DUE_7", loan, installment, 12, 7).url());
        assertEquals(inbox, EmiMessages.overdue(loan, installment, 12, -2).url());
        assertEquals(inbox, EmiMessages.forKind("OVERDUE", loan, installment, 12, -5).url());
        assertEquals("emi-" + loan.getId(), EmiMessages.due(loan, installment, 12, 10).tag(), "the tag is unchanged by the link");
    }
}
