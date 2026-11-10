package com.financeos.domain.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

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
        Map<String, Object> cardFields,
        // credit_card only: the parse's own balance checks passed (prev + charges + spends - payments == due, or purchases and payments both match the lines); null when not a card or not computed
        Boolean cardChecksOk,
        // card field keys the statement itself prints as none ("Payment Due Date: Nil"): left empty on purpose, never filled by AI
        Set<String> statedNone,
        // the parser's PARSER_VERSION that produced this draft, and the compact JSON snapshot of what it read (ParseSnapshot); null when not from the parser
        Integer parserVersion,
        String parseSnapshot
) {
    public StatementDraft {
        statedNone = statedNone == null ? Set.of() : Set.copyOf(statedNone);
    }

    public StatementDraft(String statementType, LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
                          BigDecimal closingBalance, String bankName, String accountNumberMasked, int transactionCount,
                          int linesSkipped, BigDecimal totalDebits, BigDecimal totalCredits, String parseMode,
                          BigDecimal chainValidationPct, Boolean checksumOk, StatementVerdict verdict,
                          Map<String, Object> cardFields, Boolean cardChecksOk, Set<String> statedNone) {
        this(statementType, periodStart, periodEnd, openingBalance, closingBalance, bankName, accountNumberMasked,
                transactionCount, linesSkipped, totalDebits, totalCredits, parseMode, chainValidationPct, checksumOk,
                verdict, cardFields, cardChecksOk, statedNone, null, null);
    }

    public StatementDraft(String statementType, LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
                          BigDecimal closingBalance, String bankName, String accountNumberMasked, int transactionCount,
                          int linesSkipped, BigDecimal totalDebits, BigDecimal totalCredits, String parseMode,
                          BigDecimal chainValidationPct, Boolean checksumOk, StatementVerdict verdict,
                          Map<String, Object> cardFields, Boolean cardChecksOk) {
        this(statementType, periodStart, periodEnd, openingBalance, closingBalance, bankName, accountNumberMasked,
                transactionCount, linesSkipped, totalDebits, totalCredits, parseMode, chainValidationPct, checksumOk,
                verdict, cardFields, cardChecksOk, Set.of(), null, null);
    }

    public StatementDraft(String statementType, LocalDate periodStart, LocalDate periodEnd, BigDecimal openingBalance,
                          BigDecimal closingBalance, String bankName, String accountNumberMasked, int transactionCount,
                          int linesSkipped, BigDecimal totalDebits, BigDecimal totalCredits, String parseMode,
                          BigDecimal chainValidationPct, Boolean checksumOk, StatementVerdict verdict,
                          Map<String, Object> cardFields) {
        this(statementType, periodStart, periodEnd, openingBalance, closingBalance, bankName, accountNumberMasked,
                transactionCount, linesSkipped, totalDebits, totalCredits, parseMode, chainValidationPct, checksumOk,
                verdict, cardFields, null);
    }
}
