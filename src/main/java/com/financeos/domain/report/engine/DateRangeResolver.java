package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.definition.FilterClause;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Resolves relative date operators (this_month, last_x_days, current_fy, ...) into concrete
 * {@link DateRange}s, and computes the preceding equal-length window used by KPI comparisons.
 *
 * <p>All ranges are inclusive on both ends. "Today" is the server-local date. The fiscal year
 * start month is configurable via {@code financeos.reports.fiscal-year-start-month}
 * (default 4 = April).
 */
@Component
public class DateRangeResolver {

    /** Operators whose preceding period is calendar-aligned rather than a flat day shift. */
    private static final Set<String> WEEK_ALIGNED = Set.of("this_week", "previous_week");
    private static final Set<String> MONTH_ALIGNED = Set.of("this_month", "previous_month");
    private static final Set<String> YEAR_ALIGNED = Set.of("this_year", "previous_year");
    private static final Set<String> FY_ALIGNED = Set.of("current_fy", "prev_fy");

    private final int fiscalYearStartMonth;

    public DateRangeResolver(
            @Value("${financeos.reports.fiscal-year-start-month:4}") int fiscalYearStartMonth) {
        if (fiscalYearStartMonth < 1 || fiscalYearStartMonth > 12) {
            throw new IllegalArgumentException("fiscal-year-start-month must be 1-12");
        }
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    /** Resolve a relative date operator against today's date. */
    public DateRange resolveRelative(String operator, JsonNode value) {
        return resolveRelative(operator, value, LocalDate.now());
    }

    /** Resolve a relative date operator against a supplied "today" (exposed for testing). */
    public DateRange resolveRelative(String operator, JsonNode value, LocalDate today) {
        return switch (operator) {
            case "today" -> DateRange.of(today, today);
            case "yesterday" -> DateRange.of(today.minusDays(1), today.minusDays(1));
            case "this_week" -> {
                LocalDate from = monday(today);
                yield DateRange.of(from, from.plusDays(6));
            }
            case "previous_week" -> {
                LocalDate from = monday(today).minusWeeks(1);
                yield DateRange.of(from, from.plusDays(6));
            }
            case "this_month" -> {
                LocalDate from = today.withDayOfMonth(1);
                yield DateRange.of(from, from.plusMonths(1).minusDays(1));
            }
            case "previous_month" -> {
                LocalDate firstOfThis = today.withDayOfMonth(1);
                yield DateRange.of(firstOfThis.minusMonths(1), firstOfThis.minusDays(1));
            }
            case "this_year" -> {
                LocalDate from = today.withDayOfYear(1);
                yield DateRange.of(from, from.plusYears(1).minusDays(1));
            }
            case "previous_year" -> {
                LocalDate firstOfThis = today.withDayOfYear(1);
                yield DateRange.of(firstOfThis.minusYears(1), firstOfThis.minusDays(1));
            }
            case "current_fy" -> {
                LocalDate from = fiscalYearStart(today);
                yield DateRange.of(from, from.plusYears(1).minusDays(1));
            }
            case "prev_fy" -> {
                LocalDate currentFyStart = fiscalYearStart(today);
                yield DateRange.of(currentFyStart.minusYears(1), currentFyStart.minusDays(1));
            }
            case "last_x_days" -> {
                int n = positiveAmount(value, operator);
                yield DateRange.of(today.minusDays(n - 1L), today);
            }
            case "last_x_months" -> {
                int n = positiveAmount(value, operator);
                yield DateRange.of(today.minusMonths(n).plusDays(1), today);
            }
            case "last_x_years" -> {
                int n = positiveAmount(value, operator);
                yield DateRange.of(today.minusYears(n).plusDays(1), today);
            }
            case "all_time" -> DateRange.unbounded();
            default -> throw new IllegalArgumentException("Not a relative date operator: " + operator);
        };
    }

    /**
     * The equal-length window immediately preceding {@code current}. For calendar-named ranges
     * (this_month / this_year / current_fy / week ranges) this is the previous calendar unit;
     * for rolling/explicit ranges it is a flat shift by the window length.
     */
    public DateRange previousPeriod(String operator, DateRange current) {
        if (!current.bounded()) {
            return DateRange.unbounded();
        }
        if (WEEK_ALIGNED.contains(operator) || MONTH_ALIGNED.contains(operator)
                || YEAR_ALIGNED.contains(operator) || FY_ALIGNED.contains(operator)) {
            LocalDate newTo = current.from().minusDays(1);
            LocalDate newFrom;
            if (WEEK_ALIGNED.contains(operator)) {
                newFrom = monday(newTo);
            } else if (MONTH_ALIGNED.contains(operator)) {
                newFrom = newTo.withDayOfMonth(1);
            } else if (YEAR_ALIGNED.contains(operator)) {
                newFrom = newTo.withDayOfYear(1);
            } else {
                newFrom = fiscalYearStart(newTo);
            }
            return DateRange.of(newFrom, newTo);
        }
        // Rolling / explicit (last_x_*, between, is, today, yesterday): flat shift by length.
        long len = current.lengthDays();
        return DateRange.of(current.from().minusDays(len), current.to().minusDays(len));
    }

    /** The first {@code date} filter in the list, or null if there is none. */
    public FilterClause findDateFilter(List<FilterClause> filters) {
        if (filters == null) {
            return null;
        }
        for (FilterClause filter : filters) {
            if ("date".equals(filter.field())) {
                return filter;
            }
        }
        return null;
    }

    /**
     * The concrete window implied by a date filter, used for meta + comparison. Open-ended
     * operators (after/before) and {@code all_time} resolve to an unbounded range.
     */
    public DateRange effectiveRange(FilterClause dateFilter) {
        if (dateFilter == null) {
            return DateRange.unbounded();
        }
        String op = dateFilter.operator();
        return switch (op) {
            case "between" -> DateRange.of(
                    LocalDate.parse(dateFilter.value().get("from").asText()),
                    LocalDate.parse(dateFilter.value().get("to").asText()));
            case "is" -> {
                LocalDate day = LocalDate.parse(dateFilter.value().asText());
                yield DateRange.of(day, day);
            }
            case "after", "before" -> DateRange.unbounded();
            default -> resolveRelative(op, dateFilter.value());
        };
    }

    /** The fiscal year start on or before {@code date}. */
    public LocalDate fiscalYearStart(LocalDate date) {
        LocalDate candidate = LocalDate.of(date.getYear(), fiscalYearStartMonth, 1);
        return date.isBefore(candidate) ? candidate.minusYears(1) : candidate;
    }

    private static LocalDate monday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static int positiveAmount(JsonNode value, String operator) {
        JsonNode amount = value == null ? null : value.get("amount");
        if (amount == null || !amount.isIntegralNumber() || amount.asInt() <= 0) {
            throw new IllegalArgumentException("Operator '" + operator + "' requires { amount: positive integer }");
        }
        return amount.asInt();
    }
}
