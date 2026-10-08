package com.financeos.api.inbox.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * One inbox row. {@code key} is the stable identity the snooze/dismiss state hangs off
 * ({@code bill:<statementId>}, {@code emi:<loanId>:<seq>}, ...); {@code rowType} is
 * {@code item} (one thing to act on) or {@code summary} (a count of things behind one link);
 * {@code severity} is {@code critical | warning | info} and {@code section}
 * {@code act_now | needs_look | info}.
 */
public record InboxItemResponse(
        String key,
        String kind,
        String rowType,
        String severity,
        String section,
        String title,
        @Nullable String subtitle,
        @Nullable String href,
        @Nullable BigDecimal amount,
        @Nullable LocalDate date,
        @Nullable Integer count,
        List<InboxActionResponse> actions,
        @Nullable LocalDate snoozedUntil,
        InboxRefsResponse refs
) {
    public static final String ROW_ITEM = "item";
    public static final String ROW_SUMMARY = "summary";

    public static final String SEVERITY_CRITICAL = "critical";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFO = "info";

    public static final String SECTION_ACT_NOW = "act_now";
    public static final String SECTION_NEEDS_LOOK = "needs_look";
    public static final String SECTION_INFO = "info";

    /** Not part of the wire shape (rowType already says it); ignored so Jackson/springdoc add no "summary" field. */
    @JsonIgnore
    public boolean isSummary() {
        return ROW_SUMMARY.equals(rowType);
    }
}
