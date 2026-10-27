package com.financeos.domain.epf.passbook;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ClosingBalance(BigDecimal employee, BigDecimal employer, LocalDate asOf) {
}
