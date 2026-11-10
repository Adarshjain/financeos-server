package com.financeos.statement.parser;

/** Runtime-tunable limits for the static statement parser. */
public final class ParserLimits {

    public static final int DEFAULT_MAX_PDF_PAGES = 600;
    private static volatile int maxPdfPages = DEFAULT_MAX_PDF_PAGES;

    private ParserLimits() {
    }

    public static int maxPdfPages() {
        return maxPdfPages;
    }

    public static void setMaxPdfPages(int pages) {
        if (pages < 1) {
            throw new IllegalArgumentException("max PDF pages must be >= 1, got " + pages);
        }
        maxPdfPages = pages;
    }
}
