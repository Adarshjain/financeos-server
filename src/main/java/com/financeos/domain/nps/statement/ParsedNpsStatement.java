package com.financeos.domain.nps.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Typed result of parsing a Protean NPS transaction statement. */
public record ParsedNpsStatement(
        String pran,
        String subscriberName,
        LocalDate periodStart,
        LocalDate periodEnd,
        Layout layout,
        Map<String, String> tierStatuses,
        String schemeChoice,
        List<Pref> prefs,
        Summary summary,
        List<Holding> holdings,
        List<ContributionRow> contributions,
        List<Txn> txns,
        List<String> warnings) {

    public enum Layout { WIDE, STACKED }

    public record Pref(String schemeName, BigDecimal percent) {}

    public record Summary(BigDecimal value, Integer contributionCount, BigDecimal totalContribution,
            BigDecimal totalWithdrawal, BigDecimal notionalGain, BigDecimal chargesInUnits, BigDecimal xirrPercent) {}

    public record Holding(String schemeName, BigDecimal units, BigDecimal nav, LocalDate navDate, BigDecimal value) {}

    public record ContributionRow(LocalDate date, String particulars, String uploadedBy, BigDecimal employee,
            BigDecimal employer, BigDecimal total) {}

    /** amount is signed (null for open/close); units signed (the balance for OPENING/CLOSING). */
    public record Txn(String schemeName, LocalDate date, String description, NpsTxnKind kind, BigDecimal amount,
            BigDecimal nav, BigDecimal units, BigDecimal chargeTotal) {}
}
