package com.financeos.domain.report.underlying;

/**
 * One KPI filter clause rendered for people.
 *
 * @param field      the filtered catalog field
 * @param fieldLabel the field's display label
 * @param operator   the clause's operator in JSON form
 * @param text       the right-hand side including the verb, with ids resolved to labels and
 *                   dates as dd/mm/yyyy, e.g. {@code is HDFC Regalia}, {@code in Food, Fuel},
 *                   {@code This month}
 */
public record UnderlyingFilterChip(String field, String fieldLabel, String operator, String text) {
}
