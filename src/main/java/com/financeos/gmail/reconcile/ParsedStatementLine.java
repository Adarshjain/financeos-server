package com.financeos.gmail.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedStatementLine(
    LocalDate date,
    BigDecimal amount,
    String direction, // "DEBIT" or "CREDIT"
    String description,
    BigDecimal balance,
    Boolean chainValid,
    String cardLast4
) {
    public ParsedStatementLine(
            LocalDate date,
            BigDecimal amount,
            String direction,
            String description,
            BigDecimal balance,
            Boolean chainValid) {
        this(date, amount, direction, description, balance, chainValid, null);
    }
}
