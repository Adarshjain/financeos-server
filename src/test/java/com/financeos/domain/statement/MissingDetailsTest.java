package com.financeos.domain.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.api.bill.dto.CardBillResponse;
import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.PaidSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MissingDetailsTest {

    private static Statement card(StatementVerdict verdict, LocalDate start, LocalDate end, LocalDate due,
                                  BigDecimal total, BigDecimal min) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setStatementType("credit_card");
        s.setVerdict(verdict);
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(Instant.now());
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setPaymentDueDate(due);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(min);
        s.setCreditCardDetails(d);
        return s;
    }

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final LocalDate DUE = LocalDate.of(2026, 10, 18);

    @Test
    void completeStatementReportsNothing() {
        Statement s = card(StatementVerdict.AUTO_INGEST, START, END, DUE, BigDecimal.TEN, BigDecimal.ONE);
        assertTrue(MissingDetails.of(s).isEmpty());
    }

    @Test
    void everyEmptyFigureIsNamedInOrder() {
        Statement s = card(StatementVerdict.NEEDS_REVIEW, null, null, null, null, null);
        assertEquals(List.of("PERIOD_END", "DUE_DATE", "TOTAL_DUE", "MINIMUM_DUE"), MissingDetails.of(s));
        assertEquals(List.of("DUE_DATE", "MINIMUM_DUE"),
                MissingDetails.of(card(null, START, END, null, BigDecimal.TEN, null)));
    }

    @Test
    void aMissingPeriodStartAloneIsNotMissing() {
        assertTrue(MissingDetails.of(card(StatementVerdict.AUTO_INGEST, null, END, DUE, BigDecimal.TEN, BigDecimal.ONE)).isEmpty());
    }

    @Test
    void rejectedAndNonCardStatementsReportNothing() {
        assertTrue(MissingDetails.of(card(StatementVerdict.REJECTED, null, null, null, null, null)).isEmpty());
        Statement bank = card(StatementVerdict.AUTO_INGEST, null, null, null, null, null);
        bank.setStatementType("bank_account");
        assertTrue(MissingDetails.of(bank).isEmpty());
    }

    @Test
    void aCardStatementWithoutADetailsRowLacksThePaymentFigures() {
        Statement s = card(StatementVerdict.AUTO_INGEST, START, END, DUE, BigDecimal.TEN, BigDecimal.ONE);
        s.setCreditCardDetails(null);
        assertEquals(List.of("DUE_DATE", "TOTAL_DUE", "MINIMUM_DUE"), MissingDetails.of(s));
    }

    @Test
    void summaryAndDetailDtosCarryIt() {
        Statement s = card(StatementVerdict.AUTO_INGEST, START, END, null, BigDecimal.TEN, BigDecimal.ONE);
        assertEquals(List.of("DUE_DATE"), StatementSummaryResponse.from(s).missingDetails());
        assertEquals(List.of("DUE_DATE"), StatementDetailResponse.from(s, null, List.of(), null, null).missingDetails());
        Statement ok = card(StatementVerdict.AUTO_INGEST, START, END, DUE, BigDecimal.TEN, BigDecimal.ONE);
        assertEquals(List.of(), StatementSummaryResponse.from(ok).missingDetails());
    }

    @Test
    void billResponseCarriesItAndAnAwaitingRowHasNone() {
        UUID account = UUID.randomUUID();
        CardBill bill = new CardBill(account, "Card", "1234", UUID.randomUUID(), START, END, null, BigDecimal.TEN,
                BigDecimal.ONE, null, BigDecimal.TEN, PaidSource.NONE, BillStatus.DUE_UNKNOWN, null, null, false,
                null, null, null, null, null, null);
        assertEquals(List.of("DUE_DATE"), CardBillResponse.from(bill).missingDetails());
        CardBill awaiting = new CardBill(account, "Card", "1234", null, null, null, null, null, null, null, null,
                PaidSource.NONE, BillStatus.AWAITING_STATEMENT, null, null, false, null, null, null, null, null, null);
        assertEquals(List.of(), CardBillResponse.from(awaiting).missingDetails());
    }
}
