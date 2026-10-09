package com.financeos.domain.report.underlying;

import com.financeos.domain.report.engine.TableData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes raw-table pages as CSV for spreadsheets: UTF-8 with a byte-order mark, CRLF line ends,
 * RFC 4180 quoting (a field holding a comma, quote or line break is quoted, quotes doubled).
 * Cells read as people see them: enum values by their column's label (bank_account as "Bank
 * account"), dates dd/mm/yyyy, currency and percent with two decimals, other numbers plain (no
 * trailing zeros, no exponent), booleans Yes/No, lists comma-joined, null empty.
 * Text that a spreadsheet would read as a formula (starting with = + - @ tab or CR) gets a leading
 * apostrophe, so bank narrations and names can never run as formulas when the file is opened.
 */
final class UnderlyingCsvWriter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String CRLF = "\r\n";

    private final Writer out;

    /** Starts the document (the byte-order mark) on {@code stream}. */
    UnderlyingCsvWriter(OutputStream stream) throws IOException {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        out.write('﻿');
    }

    /** The header row: one label per column. */
    void header(List<TableData.Column> columns) throws IOException {
        out.write(columns.stream().map(c -> escape(c.label())).collect(Collectors.joining(",")));
        out.write(CRLF);
    }

    /** Every row of {@code page}, then flushes so the page reaches the client before the next is read. */
    void rows(TableData page) throws IOException {
        for (Map<String, Object> row : page.rows()) {
            out.write(page.columns().stream()
                    .map(c -> escape(cell(labelled(row.get(c.key()), c.valueLabels()), c.format())))
                    .collect(Collectors.joining(",")));
            out.write(CRLF);
        }
        out.flush();
    }

    /** The value as its column labels it: a stored enum value (or each one of a list) by its label. */
    static Object labelled(Object value, Map<String, String> valueLabels) {
        if (valueLabels == null) {
            return value;
        }
        return switch (value) {
            case String s -> valueLabels.getOrDefault(s, s);
            case Collection<?> values -> values.stream()
                    .map(v -> v instanceof String s ? valueLabels.getOrDefault(s, s) : v)
                    .toList();
            case null, default -> value;
        };
    }

    static String cell(Object value, String format) {
        return switch (value) {
            case null -> "";
            case LocalDate date -> date.format(DAY);
            case Boolean b -> b ? "Yes" : "No";
            case Number n -> number(n, format);
            case Collection<?> values -> values.stream()
                    .map(v -> v instanceof Number ? String.valueOf(v) : neutralise(String.valueOf(v)))
                    .collect(Collectors.joining(", "));
            default -> neutralise(value.toString());
        };
    }

    /**
     * Prefixes text that starts with a formula trigger (= + - @ tab CR) with an apostrophe. RFC 4180
     * quoting alone does not stop Excel, LibreOffice or Sheets evaluating it. Only text passes through
     * here: numbers, dates and booleans are formatted above, so a negative amount stays numeric.
     */
    static String neutralise(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return switch (text.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + text;
            default -> text;
        };
    }

    private static String number(Number n, String format) {
        BigDecimal value = n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
        if ("currency".equals(format) || "percent".equals(format)) {
            return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return value.stripTrailingZeros().toPlainString();
    }

    static String escape(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\r') < 0 && field.indexOf('\n') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
