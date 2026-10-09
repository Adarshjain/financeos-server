package com.financeos.domain.report.underlying;

import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Enum cells in the underlying-data CSV read by their column's value labels; everything else as before. */
class UnderlyingCsvWriterValueLabelsTest {

    private static final Map<String, String> KINDS = DatasourceCatalog.valueLabels(
            "bank_account", "Bank account", "credit_card", "Credit card");

    @Test
    void aStoredValueReadsByItsLabel() {
        assertEquals("Bank account", UnderlyingCsvWriter.labelled("bank_account", KINDS));
    }

    @Test
    void aValueWithoutALabelStaysAsStored() {
        assertEquals("loan", UnderlyingCsvWriter.labelled("loan", KINDS));
    }

    @Test
    void eachValueOfAListReadsByItsLabel() {
        assertEquals(List.of("Bank account", "loan", 3), UnderlyingCsvWriter.labelled(List.of("bank_account", "loan", 3), KINDS));
    }

    @Test
    void nullsNumbersAndUnlabelledColumnsPassThrough() {
        BigDecimal amount = new BigDecimal("12");

        assertNull(UnderlyingCsvWriter.labelled(null, KINDS));
        assertSame(amount, UnderlyingCsvWriter.labelled(amount, KINDS));
        assertEquals("bank_account", UnderlyingCsvWriter.labelled("bank_account", null));
    }

    @Test
    void rowsWriteLabelledColumnsByTheirLabelsAndOtherColumnsAsStored() throws Exception {
        List<TableData.Column> columns = List.of(
                new TableData.Column("name", "Name", "string", null),
                new TableData.Column("kind", "Kind", "enum", null, KINDS),
                new TableData.Column("note", "Note", "string", null),
                new TableData.Column("value", "Value", "number", "currency"));
        List<Map<String, Object>> rows = List.of(
                row("name", "credit_card", "kind", "credit_card", "note", "bank_account", "value", new BigDecimal("5")),
                row("name", "Cash", "kind", "generic", "note", null, "value", new BigDecimal("1.5")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        UnderlyingCsvWriter csv = new UnderlyingCsvWriter(out);
        csv.header(columns);
        csv.rows(new TableData("TABLE", "raw", columns, rows, new TableData.Page(0, 1000, 2, 1)));

        byte[] bytes = out.toByteArray();
        assertEquals("Name,Kind,Note,Value\r\ncredit_card,Credit card,bank_account,5.00\r\nCash,generic,,1.50\r\n",
                new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
