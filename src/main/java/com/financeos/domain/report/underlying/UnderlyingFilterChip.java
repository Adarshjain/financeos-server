package com.financeos.domain.report.underlying;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * One KPI filter clause rendered for people.
 *
 * @param field       the filtered catalog field
 * @param fieldLabel  the field's display label
 * @param operator    the clause's operator in JSON form
 * @param text        the right-hand side including the verb, with ids resolved to labels and
 *                    dates as dd/mm/yyyy, e.g. {@code is HDFC Regalia}, {@code in Food, Fuel},
 *                    {@code This month}. Kept for clients that predate the structured parts below.
 * @param valueType   what {@code values} hold: {@code date}, {@code number}, {@code boolean} or
 *                    {@code text}
 * @param valueFormat for {@code number}: the field's format ({@code currency}, {@code number},
 *                    {@code percent}); null otherwise
 * @param datePreset  for a relative date operator with no value of its own ({@code this_month},
 *                    {@code current_fy}, {@code all_time}, {@code this_billing_cycle},
 *                    {@code previous_billing_cycle}, {@code billing_cycles_ago}); null otherwise
 * @param values      the clause's values, typed: ISO dates ({@code 2026-09-01}) for dates, display
 *                    names (ids resolved) for text, numbers for amounts, booleans. A range is
 *                    {@code [from, to]}; {@code last_x_*}/{@code next_x_days} and
 *                    {@code billing_cycles_ago} hold their count; empty for a bare preset
 */
public record UnderlyingFilterChip(String field, String fieldLabel, String operator, String text,
                                   String valueType, @Nullable String valueFormat, @Nullable String datePreset,
                                   @ArraySchema(schema = @Schema(oneOf = {String.class, java.math.BigDecimal.class,
                                           Boolean.class, Integer.class})) List<Object> values) {

    /** A chip with no structured parts (text only). */
    public UnderlyingFilterChip(String field, String fieldLabel, String operator, String text) {
        this(field, fieldLabel, operator, text, null, null, null, List.of());
    }
}
