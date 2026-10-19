package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What is paid out of an account: active loan EMIs and the credit card bills it settled lately. */
public record AccountPaymentsFromResponse(List<Emi> emis, List<CardBill> cardBills) {

    /** An active loan paid from the account; {@code nextDueDate} is null when no installment is left. */
    public record Emi(UUID loanId, String name, BigDecimal amount, @Nullable LocalDate nextDueDate) {
    }

    /** A credit card the account paid in the last 12 months, with its latest payment. */
    public record CardBill(UUID accountId, String name, LocalDate lastPaidOn, BigDecimal lastAmount) {
    }
}
