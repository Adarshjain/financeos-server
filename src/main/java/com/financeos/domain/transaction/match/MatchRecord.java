package com.financeos.domain.transaction.match;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * One piece of evidence for a payment, as the pair test sees it: a statement line, an alert, an uploaded row or a
 * manual entry. {@code currency} is null or INR for rupee records; {@code refs} come from {@link ReferenceNumbers}.
 */
public record MatchRecord(UUID accountId, TransactionType type, BigDecimal amount, LocalDate date,
                          String cardLast4, String text, String currency, Set<String> refs, BigDecimal originalAmount) {

    public MatchRecord {
        cardLast4 = last4(cardLast4);
        refs = refs == null ? Set.of() : Set.copyOf(refs);
    }

    /** A record with no original (foreign) amount. */
    public MatchRecord(UUID accountId, TransactionType type, BigDecimal amount, LocalDate date,
                       String cardLast4, String text, String currency, Set<String> refs) {
        this(accountId, type, amount, date, cardLast4, text, currency, refs, null);
    }

    /** A record for a stored transaction: its bank text (sourced description, else the typed description). */
    public static MatchRecord of(Transaction t) {
        String text = t.getSourcedDescription() != null ? t.getSourcedDescription() : t.getDescription();
        return new MatchRecord(t.getAccount() != null ? t.getAccount().getId() : null, t.getType(), t.getAmount(), t.getDate(),
                t.getCard() != null ? t.getCard().getLast4() : null, text, t.getOriginalCurrency(),
                ReferenceNumbers.extract(text), t.getOriginalAmount());
    }

    /** True for a stored transaction of a foreign charge (original currency other than INR). */
    public static boolean isForeign(Transaction t) {
        return isForeignCode(t.getOriginalCurrency());
    }

    /** True for a currency code other than INR. */
    public static boolean isForeignCode(String currency) {
        return currency != null && !currency.isBlank() && !"INR".equalsIgnoreCase(currency.trim());
    }

    /** A record for a statement line of the account. */
    public static MatchRecord of(UUID accountId, StatementBatchMatcher.Line line) {
        return new MatchRecord(accountId, line.type(), line.amount(), line.date(), line.cardLast4(), line.description(),
                null, ReferenceNumbers.extract(line.description()));
    }

    /** True for a charge in a currency other than rupees. */
    public boolean isForeign() {
        return isForeignCode(currency);
    }

    private static String last4(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\s+", "");
        if (digits.length() < 4) return null;
        return digits.substring(digits.length() - 4);
    }
}
