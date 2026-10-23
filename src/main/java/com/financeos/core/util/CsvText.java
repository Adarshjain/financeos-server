package com.financeos.core.util;

import java.util.List;
import java.util.stream.Collectors;

/** RFC 4180 CSV cells: fields with a comma, quote or line break are quoted, quotes doubled; CRLF rows. */
public final class CsvText {

    public static final String CRLF = "\r\n";

    private CsvText() {
    }

    /** One cell; null is empty. Text a spreadsheet would run as a formula gets a leading apostrophe. */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        char first = v.charAt(0);
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r') {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** A row of cells (quoting applied here), with its line end. */
    public static String row(List<String> cells) {
        return cells.stream().map(CsvText::cell).collect(Collectors.joining(",")) + CRLF;
    }
}
