package com.financeos.gmail.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedStatementLine(
    LocalDate date,
    BigDecimal amount,
    String direction, // "DEBIT" or "CREDIT"
    String description,
    BigDecimal balance,
    Boolean chainValid
) {}
