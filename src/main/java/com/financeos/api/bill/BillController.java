package com.financeos.api.bill;

import com.financeos.api.bill.dto.CardBillResponse;
import com.financeos.api.bill.dto.MarkBillPaidRequest;
import com.financeos.api.bill.dto.UpdateBillDetailsRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.bill.CardBillService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Credit-card bills: the dashboard card's data and the user's three actions on a bill. A bill is keyed by its statement. */
@RestController
@RequestMapping("/api/v1/bills")
public class BillController {

    private final CardBillService cardBillService;

    public BillController(CardBillService cardBillService) {
        this.cardBillService = cardBillService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping
    public ResponseEntity<List<CardBillResponse>> listBills() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(cardBillService.listBills(userId).stream().map(CardBillResponse::from).toList());
    }

    @GetMapping("/{statementId}")
    public ResponseEntity<CardBillResponse> getBill(@PathVariable UUID statementId) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(CardBillResponse.from(cardBillService.findByStatementId(userId, statementId)));
    }

    @PostMapping("/{statementId}/mark-paid")
    public ResponseEntity<CardBillResponse> markPaid(@PathVariable UUID statementId,
                                                     @Valid @RequestBody(required = false) MarkBillPaidRequest request) {
        UUID userId = requireCurrentUserId();
        MarkBillPaidRequest body = request != null ? request : new MarkBillPaidRequest(null, null);
        return ResponseEntity.ok(CardBillResponse.from(
                cardBillService.markPaid(userId, statementId, body.amount(), body.paidOn())));
    }

    @DeleteMapping("/{statementId}/mark-paid")
    public ResponseEntity<CardBillResponse> unmarkPaid(@PathVariable UUID statementId) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(CardBillResponse.from(cardBillService.unmarkPaid(userId, statementId)));
    }

    @PatchMapping("/{statementId}/details")
    public ResponseEntity<CardBillResponse> updateDetails(@PathVariable UUID statementId,
                                                          @Valid @RequestBody UpdateBillDetailsRequest request) {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(CardBillResponse.from(cardBillService.updateDetails(userId, statementId,
                request.paymentDueDate(), request.totalAmountDue(), request.minimumAmountDue())));
    }
}
