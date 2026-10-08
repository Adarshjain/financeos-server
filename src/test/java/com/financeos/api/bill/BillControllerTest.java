package com.financeos.api.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.bill.dto.CardBillResponse;
import com.financeos.api.bill.dto.MarkBillPaidRequest;
import com.financeos.api.bill.dto.UpdateBillDetailsRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.bill.PaidSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class BillControllerTest {

    private CardBillService service;
    private BillController controller;
    private final UUID userId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private CardBill bill;

    @BeforeEach
    void setUp() {
        service = mock(CardBillService.class);
        controller = new BillController(service);
        UserContext.setCurrentUserId(userId);
        bill = new CardBill(UUID.randomUUID(), "HDFC", "4321", statementId, null, LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 28), new BigDecimal("100"), new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("100"),
                PaidSource.NONE, BillStatus.OPEN, 8L, null,
                List.of(new CardBill.PossiblePayment(UUID.randomUUID(), LocalDate.of(2026, 10, 12), new BigDecimal("100"), "UPI")),
                false, null, "RECEIVED", LocalDate.of(2026, 10, 11),
                new CardBill.Digest(new BigDecimal("100"), null, null, null, null, null, new BigDecimal("1000"), new BigDecimal("10.0"), 3),
                new BigDecimal("250"), LocalDate.of(2026, 11, 10));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listMapsTheDomainViewOneToOne() {
        when(service.listBills(userId)).thenReturn(List.of(bill));
        ResponseEntity<List<CardBillResponse>> response = controller.listBills(null);
        assertEquals(200, response.getStatusCode().value());
        CardBillResponse body = response.getBody().get(0);
        assertEquals(statementId, body.statementId());
        assertEquals(BillStatus.OPEN, body.status());
        assertEquals(1, body.possiblePayments().size());
        assertEquals("UPI", body.possiblePayments().get(0).description());
        assertEquals(new BigDecimal("10.0"), body.digest().utilizationPct());
        assertEquals("RECEIVED", body.lastNotifiedKind());
        assertEquals(new BigDecimal("250"), body.unbilledAmount());
        assertEquals(LocalDate.of(2026, 11, 10), body.nextStatementExpectedOn());
    }

    @Test
    void actionsDelegateWithTheCurrentUser() {
        when(service.findByStatementId(userId, statementId)).thenReturn(bill);
        assertEquals(statementId, controller.getBill(statementId).getBody().statementId());

        when(service.markPaid(userId, statementId, null, null)).thenReturn(bill);
        assertEquals(200, controller.markPaid(statementId, null).getStatusCode().value());
        when(service.markPaid(userId, statementId, new BigDecimal("50"), LocalDate.of(2026, 10, 12))).thenReturn(bill);
        controller.markPaid(statementId, new MarkBillPaidRequest(new BigDecimal("50"), LocalDate.of(2026, 10, 12)));
        verify(service).markPaid(userId, statementId, new BigDecimal("50"), LocalDate.of(2026, 10, 12));

        when(service.unmarkPaid(userId, statementId)).thenReturn(bill);
        assertEquals(200, controller.unmarkPaid(statementId).getStatusCode().value());

        when(service.updateDetails(userId, statementId, LocalDate.of(2026, 11, 1), null, null)).thenReturn(bill);
        controller.updateDetails(statementId, new UpdateBillDetailsRequest(LocalDate.of(2026, 11, 1), null, null));
        verify(service).updateDetails(userId, statementId, LocalDate.of(2026, 11, 1), null, null);
    }

    @Test
    void unauthenticatedCallsAreRejected() {
        UserContext.clear();
        assertThrows(ResponseStatusException.class, () -> controller.listBills(null));
        assertThrows(ResponseStatusException.class, () -> controller.markPaid(statementId, null));
    }
}
