package com.financeos.domain.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

// Parser-agnostic snapshot of one parsed statement; card field keys follow the
// parser's summary-field vocabulary (total_amount_due, minimum_amount_due,
// payment_due_date, credit_limit, available_credit_limit, finance_charges,
// fees_and_charges, previous_balance, payments_received, total_purchases,
// reward_points_balance, reward_points_earned). Values are BigDecimal or LocalDate.
public record StatementDraft(
        String statementType,          // bank_account | credit_card
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        String bankName,
        String accountNumberMasked,
        int transactionCount,
        int linesSkipped,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        String parseMode,              // balance_chain | opening_closing | heuristic
        BigDecimal chainValidationPct,
        Boolean checksumOk,
        StatementVerdict verdict,
        Map<String, Object> cardFields
) {
}
