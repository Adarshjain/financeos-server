package com.financeos.domain.report.underlying;

import java.time.LocalDate;

/** An inclusive period window shown with KPI underlying data. */
public record UnderlyingRange(LocalDate from, LocalDate to) {
}
