package com.financeos.api.bill;

import com.financeos.api.bill.dto.CardBillResponse;
import com.financeos.api.bill.dto.MarkBillPaidRequest;
import com.financeos.api.bill.dto.PaymentCandidateResponse;
import com.financeos.api.bill.dto.UpdateBillDetailsRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.bill.BillPaymentService;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Credit-card bills: the dashboard card's data and the user's three actions on a bill. A bill is keyed by its statement. */
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

    private final CardBillService cardBillService;
    private final BillPaymentService billPaymentService;

    /** Without the payment service (plain unit tests): bills carry no payments. */
    public BillController(CardBillService cardBillService) {
        this(cardBillService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BillController(CardBillService cardBillService, BillPaymentService billPaymentService) {
        this.cardBillService = cardBillService;
        this.billPaymentService = billPaymentService;
    }

    /** The responses with each bill's recorded payments, read for all of them in one query. */
    private List<CardBillResponse> respond(UUID userId, List<CardBill> bills) {
        Map<UUID, List<BillPaymentService.PaymentView>> payments = billPaymentService == null ? Map.of()
                : billPaymentService.paymentsFor(userId, bills.stream().map(CardBill::statementId).filter(Objects::nonNull).toList());
        return bills.stream().map(b -> CardBillResponse.from(b,
                b.statementId() == null ? List.of() : payments.getOrDefault(b.statementId(), List.of()))).toList();
    }

    private CardBillResponse respond(UUID userId, CardBill bill) {
        return respond(userId, List.of(bill)).get(0);
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    /** All open cards' bills, most urgent first; {@code accountId} narrows the list to one card. */
    @GetMapping
    public ResponseEntity<List<CardBillResponse>> listBills(@RequestParam(required = false) UUID accountId) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(respond(userId, cardBillService.listBills(userId, accountId)));
    }

    /**
     * One card's bills, a bill per statement, newest period end first; each equals
     * {@code GET /bills/{statementId}}. {@code limit} keeps the newest n; {@code from}/{@code to} bound
     * the period end inclusively; neither means every statement.
     */
    @GetMapping("/history")
    public ResponseEntity<List<CardBillResponse>> history(
            @RequestParam UUID accountId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(respond(userId, cardBillService.historyForAccount(userId, accountId, limit, from, to)));
    }

    @GetMapping("/{statementId}")
    public ResponseEntity<CardBillResponse> getBill(@PathVariable UUID statementId) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(respond(userId, cardBillService.findByStatementId(userId, statementId)));
    }

    /**
     * Free bank debits that could be this bill's payment: exactly {@code amount} (default what is left), dated after the
     * statement up to ten days past the due date, payee signal first then nearest to the due date.
     */
    @GetMapping("/{statementId}/payment-candidates")
    public ResponseEntity<List<PaymentCandidateResponse>> paymentCandidates(@PathVariable UUID statementId,
                                                                          @RequestParam(required = false) BigDecimal amount) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(cardBillService.paymentCandidates(userId, statementId, amount).stream()
                .map(PaymentCandidateResponse::from).toList());
    }

    @PostMapping("/{statementId}/mark-paid")
    public ResponseEntity<CardBillResponse> markPaid(@PathVariable UUID statementId,
                                                     @Valid @RequestBody(required = false) MarkBillPaidRequest request) {
        UUID userId = requireCurrentUserId();
        MarkBillPaidRequest body = request != null ? request : new MarkBillPaidRequest(null, null);
        MarkBillPaidRequest.Payment payment = body.payment();
        return ResponseEntity.ok(respond(userId, payment == null
                ? cardBillService.markPaid(userId, statementId, body.amount(), body.paidOn())
                : cardBillService.markPaid(userId, statementId, body.amount(), body.paidOn(), payment.mode(),
                        payment.fromTransactionId(), payment.fromAccountId())));
    }

    @DeleteMapping("/{statementId}/mark-paid")
    public ResponseEntity<CardBillResponse> unmarkPaid(@PathVariable UUID statementId) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(respond(userId, cardBillService.unmarkPaid(userId, statementId)));
    }

    @PatchMapping("/{statementId}/details")
    public ResponseEntity<CardBillResponse> updateDetails(@PathVariable UUID statementId,
                                                          @Valid @RequestBody UpdateBillDetailsRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(respond(userId, cardBillService.updateDetails(userId, statementId,
                request.periodStart(), request.periodEnd(), request.paymentDueDate(), request.totalAmountDue(), request.minimumAmountDue())));
    }
}
