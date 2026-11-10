package com.financeos.statement.parser.layout;

import java.util.List;

/**
 * Receives the outcome of every shadow comparison (v1 result against the profiled reader) and every ACTIVE fallback.
 * Counts and field names only, never values.
 */
public interface ShadowListener {

    enum Kind { IDENTICAL, DIFFERENT, FAILED, FALLBACK }

    /**
     * @param layoutId        the profile that ran
     * @param kind            IDENTICAL / DIFFERENT (shadow compared), FAILED (the profiled reader threw), FALLBACK (ACTIVE
     *                        result did not pass the guard and v1 was consulted)
     * @param differingRows   rows that differ by position, plus the difference in row count
     * @param differingFields number of differing meta, parse-info and summary fields
     * @param fieldNames      names of those fields (e.g. {@code summaryFields.credit_limit}), never values
     */
    record Result(String layoutId, Kind kind, int differingRows, int differingFields, List<String> fieldNames) {
        public boolean identical() {
            return kind == Kind.IDENTICAL;
        }

        public boolean failed() {
            return kind == Kind.FAILED;
        }

        public boolean fallback() {
            return kind == Kind.FALLBACK;
        }
    }

    void onComparison(Result result);
}
