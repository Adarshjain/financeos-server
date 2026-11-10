package com.financeos.domain.statement.reparse;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementDraft;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The statement fields a reconciling refresh overwrites from the parse, by the names {@code refreshReconciled} reports.
 * Used to tell a field the refresh would change from one it would clear (stored value present, parse has none), and to
 * put a cleared field's stored value back. Field names only ever leave this class, never values (except back to the entity).
 */
final class StatementFields {

    /** Stored values by field name, as they were before a refresh. */
    record Stored(Map<String, Object> values) {
        static final Stored NONE = new Stored(Map.of());
    }

    /** The reported names split into replaced/filled ({@code changed}) and cleared by the parse ({@code cleared}). */
    record Split(List<String> changed, List<String> cleared) {}

    private StatementFields() {}

    static Stored snapshot(Statement s) {
        if (s == null) {
            return Stored.NONE;
        }
        Map<String, Object> v = new HashMap<>();
        v.put("opening_balance", s.getOpeningBalance());
        v.put("closing_balance", s.getClosingBalance());
        v.put("total_debits", s.getTotalDebits());
        v.put("total_credits", s.getTotalCredits());
        v.put("lines_skipped", s.getLinesSkipped());
        v.put("transaction_count", s.getTransactionCount());
        v.put("parse_mode", s.getParseMode());
        v.put("chain_validation_pct", s.getChainValidationPct());
        v.put("checksum", s.getChecksumOk());
        v.put("verdict", s.getVerdict());
        StatementCreditCardDetails d = s.getCreditCardDetails();
        if (d != null) {
            v.put("total_amount_due", d.getTotalAmountDue());
            v.put("minimum_amount_due", d.getMinimumAmountDue());
            v.put("payment_due_date", d.getPaymentDueDate());
            v.put("credit_limit", d.getCreditLimit());
            v.put("available_credit_limit", d.getAvailableCreditLimit());
            v.put("finance_charges", d.getFinanceCharges());
            v.put("fees_and_charges", d.getFeesAndCharges());
            v.put("previous_balance", d.getPreviousBalance());
            v.put("payments_received", d.getPaymentsReceived());
            v.put("total_purchases", d.getTotalPurchases());
            v.put("reward_points_balance", d.getRewardPointsBalance());
            v.put("reward_points_earned", d.getRewardPointsEarned());
        }
        return new Stored(v);
    }

    private static Object parsed(StatementDraft d, String name) {
        return switch (name) {
            case "opening_balance" -> d.openingBalance();
            case "closing_balance" -> d.closingBalance();
            case "total_debits" -> d.totalDebits();
            case "total_credits" -> d.totalCredits();
            case "lines_skipped" -> d.linesSkipped();
            case "transaction_count" -> d.transactionCount();
            case "parse_mode" -> d.parseMode();
            case "chain_validation_pct" -> d.chainValidationPct();
            case "checksum" -> d.checksumOk();
            case "verdict" -> d.verdict();
            default -> d.cardFields() == null ? null : d.cardFields().get(name);
        };
    }

    /** A field is cleared when a stored value exists and the parse has none; the period and unknown names are changes. */
    static Split split(Stored stored, StatementDraft draft, List<String> reported) {
        List<String> changed = new ArrayList<>();
        List<String> cleared = new ArrayList<>();
        for (String name : reported) {
            if (!"period".equals(name) && stored.values().get(name) != null && parsed(draft, name) == null) {
                cleared.add(name);
            } else {
                changed.add(name);
            }
        }
        return new Split(changed, cleared);
    }

    /** Puts the stored value of each named field back on the statement (inside the refresh's own transaction). */
    static void restore(Statement s, Stored stored, List<String> names) {
        StatementCreditCardDetails d = s.getCreditCardDetails();
        for (String name : names) {
            Object v = stored.values().get(name);
            switch (name) {
                case "opening_balance" -> s.setOpeningBalance((BigDecimal) v);
                case "closing_balance" -> s.setClosingBalance((BigDecimal) v);
                case "total_debits" -> s.setTotalDebits((BigDecimal) v);
                case "total_credits" -> s.setTotalCredits((BigDecimal) v);
                case "lines_skipped" -> s.setLinesSkipped((Integer) v);
                case "transaction_count" -> s.setTransactionCount((Integer) v);
                case "parse_mode" -> s.setParseMode((String) v);
                case "chain_validation_pct" -> s.setChainValidationPct((BigDecimal) v);
                case "checksum" -> s.setChecksumOk((Boolean) v);
                case "verdict" -> s.setVerdict((com.financeos.domain.statement.StatementVerdict) v);
                default -> {
                    if (d == null) break;
                    switch (name) {
                        case "total_amount_due" -> d.setTotalAmountDue((BigDecimal) v);
                        case "minimum_amount_due" -> d.setMinimumAmountDue((BigDecimal) v);
                        case "payment_due_date" -> d.setPaymentDueDate((LocalDate) v);
                        case "credit_limit" -> d.setCreditLimit((BigDecimal) v);
                        case "available_credit_limit" -> d.setAvailableCreditLimit((BigDecimal) v);
                        case "finance_charges" -> d.setFinanceCharges((BigDecimal) v);
                        case "fees_and_charges" -> d.setFeesAndCharges((BigDecimal) v);
                        case "previous_balance" -> d.setPreviousBalance((BigDecimal) v);
                        case "payments_received" -> d.setPaymentsReceived((BigDecimal) v);
                        case "total_purchases" -> d.setTotalPurchases((BigDecimal) v);
                        case "reward_points_balance" -> d.setRewardPointsBalance((BigDecimal) v);
                        case "reward_points_earned" -> d.setRewardPointsEarned((BigDecimal) v);
                        default -> { }
                    }
                }
            }
        }
    }
}
