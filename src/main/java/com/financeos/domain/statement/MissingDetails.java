package com.financeos.domain.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Which of the four figures (period end, due date, total and minimum due; a missing period start never counts) a credit-card statement must carry are still empty. Computed on read,
 * never stored: filling a gap (edit or re-upload) clears it. A rejected statement is not a bill, so
 * it reports nothing.
 */
public final class MissingDetails {

    public static final String PERIOD_END = "PERIOD_END";
    /** Only ever in a re-upload's filled list: the stored period was replaced by the parsed one. */
    public static final String PERIOD_UPDATED = "PERIOD_UPDATED";
    public static final String DUE_DATE = "DUE_DATE";
    public static final String TOTAL_DUE = "TOTAL_DUE";
    public static final String MINIMUM_DUE = "MINIMUM_DUE";

    private MissingDetails() {
    }

    /** Empty for anything but a non-rejected credit-card statement with a gap. */
    public static List<String> of(@Nullable Statement statement) {
        if (statement == null || !"credit_card".equals(statement.getStatementType())) {
            return List.of();
        }
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        return of(statement.getVerdict(), statement.getPeriodStart(), statement.getPeriodEnd(),
                d == null ? null : d.getPaymentDueDate(), d == null ? null : d.getTotalAmountDue(),
                d == null ? null : d.getMinimumAmountDue());
    }

    public static List<String> of(@Nullable StatementVerdict verdict, @Nullable LocalDate periodStart,
                                  @Nullable LocalDate periodEnd, @Nullable LocalDate dueDate,
                                  @Nullable BigDecimal totalDue, @Nullable BigDecimal minimumDue) {
        if (verdict == StatementVerdict.REJECTED) {
            return List.of();
        }
        List<String> missing = new ArrayList<>(5);
        if (periodEnd == null) {
            missing.add(PERIOD_END);
        }
        if (dueDate == null) {
            missing.add(DUE_DATE);
        }
        if (totalDue == null) {
            missing.add(TOTAL_DUE);
        }
        if (minimumDue == null) {
            missing.add(MINIMUM_DUE);
        }
        return List.copyOf(missing);
    }

    /** The field in words, for a sentence. */
    public static String words(String field) {
        return switch (field) {
            case PERIOD_END -> "period end";
            case DUE_DATE -> "due date";
            case TOTAL_DUE -> "total due";
            case MINIMUM_DUE -> "minimum due";
            case PERIOD_UPDATED -> "period";
            default -> field;
        };
    }
}
