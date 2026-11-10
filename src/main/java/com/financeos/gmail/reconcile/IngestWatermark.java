package com.financeos.gmail.reconcile;

import java.time.LocalDate;
import java.util.List;

/**
 * The Gmail ingest's watermark rule in one place: statement lines dated before the account's {@code ingestFromDate}
 * are dropped before matching, and the statement's links index into the list that remains. The statement re-read job
 * applies the same rule to a gmail statement's re-parse so it compares like with like.
 */
public final class IngestWatermark {

    private IngestWatermark() {
    }

    /** True when the line is inside the window: no watermark, or dated on or after it. */
    public static boolean keeps(ParsedStatementLine line, LocalDate watermark) {
        return watermark == null || !line.date().isBefore(watermark);
    }

    /** The lines dated on or after the watermark, in order; all of them when there is no watermark. */
    public static List<ParsedStatementLine> filter(List<ParsedStatementLine> lines, LocalDate watermark) {
        return lines.stream().filter(line -> keeps(line, watermark)).toList();
    }
}
