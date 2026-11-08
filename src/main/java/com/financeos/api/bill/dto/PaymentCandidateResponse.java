package com.financeos.api.bill.dto;

import com.financeos.domain.notification.bill.BillPaymentService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** A free bank debit that could be a bill's payment. {@code payeeSignal}: its text reads as a card-bill payee. */
public record PaymentCandidateResponse(UUID transactionId, UUID accountId, String accountName, LocalDate date,
                                       BigDecimal amount, @Nullable String description, boolean payeeSignal) {
    public static PaymentCandidateResponse from(BillPaymentService.CandidateView c) {
        return new PaymentCandidateResponse(c.transactionId(), c.accountId(), c.accountName(), c.date(), c.amount(),
                c.description(), c.payeeSignal());
    }
}
