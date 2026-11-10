package com.financeos.statement.parser.layout;

/** How a layout profile takes part in parsing: not at all, compared with v1 in the background, or as the reader. */
public enum ReaderMode {
    OFF, SHADOW, ACTIVE;

    public static ReaderMode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return valueOf(text.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
