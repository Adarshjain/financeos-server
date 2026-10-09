package com.financeos.domain.report.engine;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;

import java.util.Optional;

/**
 * Parses the {@code sort} query parameter of report runs: exactly one {@code <key>,<asc|desc>}
 * clause (direction case-insensitive, surrounding whitespace ignored). Only the syntax is checked
 * here; whether the key is sortable for a definition is the executor's concern.
 */
public final class RuntimeSort {

    private RuntimeSort() {
    }

    /**
     * The runtime sort clause, or empty when {@code raw} is null or blank (the report's own order applies).
     *
     * @throws ValidationException when {@code raw} is not a single {@code key,asc|desc} clause
     */
    public static Optional<SortClause> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalid(raw);
        }
        SortDirection direction;
        try {
            direction = SortDirection.from(parts[1].trim());
        } catch (IllegalArgumentException e) {
            throw invalid(raw);
        }
        return Optional.of(new SortClause(parts[0].trim(), direction));
    }

    private static ValidationException invalid(String raw) {
        return new ValidationException(
                "Invalid sort '" + raw + "': expected one '<column>,asc' or '<column>,desc' clause");
    }
}
