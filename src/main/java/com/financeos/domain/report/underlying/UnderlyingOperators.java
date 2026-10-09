package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.definition.FilterClause;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Internal filter operators that narrow a KPI period's rows to the rows its underlying data lists.
 * They are never offered in the catalog nor accepted by the validator (like
 * {@code billing_cycles_ago}); only the SQL query builders and the in-memory matcher handle them.
 * <ul>
 *   <li>{@link #PRESENT}: the field has a value ({@code IS NOT NULL}) — every aggregation skips
 *       rows without one, so they never contribute to the figure;</li>
 *   <li>{@link #EQUAL_TO}: the field equals the scalar number in the clause's value — for MIN/MAX
 *       only the row(s) that set the figure are listed.</li>
 * </ul>
 */
public final class UnderlyingOperators {

    public static final String PRESENT = "underlying_present";
    public static final String EQUAL_TO = "underlying_equal_to";

    private static final Set<String> ALL = Set.of(PRESENT, EQUAL_TO);

    private UnderlyingOperators() {
    }

    public static boolean isInternal(@Nullable String operator) {
        return operator != null && ALL.contains(operator);
    }

    /**
     * The clauses that keep exactly the rows behind a KPI value: rows whose {@code measure} is
     * present and, for MIN/MAX, equal to {@code value}. A MIN/MAX without a value (no row has the
     * measure) lists nothing, which {@link #PRESENT} already guarantees.
     */
    public static List<FilterClause> listing(String measure, Aggregation aggregation, @Nullable BigDecimal value) {
        List<FilterClause> clauses = new ArrayList<>();
        clauses.add(new FilterClause(measure, PRESENT, null));
        if (winnerOnly(aggregation) && value != null) {
            clauses.add(new FilterClause(measure, EQUAL_TO, JsonNodeFactory.instance.numberNode(value)));
        }
        return clauses;
    }

    /** Whether a KPI's underlying data lists only the row(s) that set its value (MIN/MAX). */
    public static boolean winnerOnly(Aggregation aggregation) {
        return aggregation == Aggregation.MIN || aggregation == Aggregation.MAX;
    }
}
