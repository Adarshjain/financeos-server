package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeSortTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void absentOrBlankMeansNoRuntimeSort(String raw) {
        assertTrue(RuntimeSort.parse(raw).isEmpty());
    }

    @Test
    void parsesKeyAndDirection() {
        assertEquals(Optional.of(new SortClause("date", SortDirection.DESC)), RuntimeSort.parse("date,desc"));
        assertEquals(Optional.of(new SortClause("amount_sum", SortDirection.ASC)), RuntimeSort.parse("amount_sum,asc"));
    }

    @Test
    void directionIsCaseInsensitive() {
        assertEquals(SortDirection.DESC, RuntimeSort.parse("date,DESC").orElseThrow().direction());
        assertEquals(SortDirection.ASC, RuntimeSort.parse("date,Asc").orElseThrow().direction());
    }

    @Test
    void trimsWhitespaceAroundKeyAndDirection() {
        assertEquals(Optional.of(new SortClause("date", SortDirection.ASC)), RuntimeSort.parse(" date , asc "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "date",              // no direction
            "date,desc,amount",  // more than one clause
            "date,desc,",        // trailing separator
            ",asc",              // blank key
            " ,asc",             // whitespace-only key
            "date,",             // blank direction
            "date,up"            // unknown direction
    })
    void malformedValueIsRejected(String raw) {
        ValidationException e = assertThrows(ValidationException.class, () -> RuntimeSort.parse(raw));
        assertEquals("Invalid sort '" + raw + "': expected one '<column>,asc' or '<column>,desc' clause", e.getMessage());
    }
}
