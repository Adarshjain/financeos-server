package com.financeos.core.observability;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;

public final class ParseLogger {

    private ParseLogger() {}

    public static long started(Logger log, String parser, int sizeBytes, String filename) {
        log.info("Parse started: parser={}, sizeBytes={}", parser, sizeBytes,
                StructuredArguments.keyValue("event", Events.PARSE_STARTED),
                StructuredArguments.keyValue("parser", parser),
                StructuredArguments.keyValue("sizeBytes", sizeBytes),
                StructuredArguments.keyValue("filename", filename != null ? filename : ""));
        return System.currentTimeMillis();
    }

    public static void completed(Logger log, String parser, int rowCount, String headerRaw, long startTimeMs) {
        long durationMs = System.currentTimeMillis() - startTimeMs;
        String headerFingerprint = formatHeaderFingerprint(headerRaw);
        log.info("Parse completed: parser={}, rowCount={}, durationMs={}, headerFingerprint={}", parser, rowCount, durationMs, headerFingerprint,
                StructuredArguments.keyValue("event", Events.PARSE_COMPLETED),
                StructuredArguments.keyValue("parser", parser),
                StructuredArguments.keyValue("rowCount", rowCount),
                StructuredArguments.keyValue("durationMs", durationMs),
                StructuredArguments.keyValue("headerFingerprint", headerFingerprint));
    }

    public static void rejectedRow(Logger log, String parser, int rowIndex, String reason) {
        log.debug("Parse row rejected: parser={}, rowIndex={}, reason={}", parser, rowIndex, reason,
                StructuredArguments.keyValue("event", Events.PARSE_ROW_REJECTED),
                StructuredArguments.keyValue("parser", parser),
                StructuredArguments.keyValue("rowIndex", rowIndex),
                StructuredArguments.keyValue("reason", reason));
    }

    public static void failed(Logger log, String parser, String stage, Integer rowIndex, String reason, Throwable t) {
        log.error("Parse failed: parser={}, stage={}, rowIndex={}, reason={}", parser, stage, rowIndex, reason,
                StructuredArguments.keyValue("event", Events.PARSE_FAILED),
                StructuredArguments.keyValue("parser", parser),
                StructuredArguments.keyValue("stage", stage),
                StructuredArguments.keyValue("rowIndex", rowIndex != null ? rowIndex : -1),
                StructuredArguments.keyValue("reason", reason),
                t);
    }

    private static String formatHeaderFingerprint(String headerRaw) {
        if (headerRaw == null || headerRaw.isBlank()) {
            return "none";
        }
        String trimmed = headerRaw.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
