package com.financeos.domain.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileIngestionResultPageCapClassifyTest {

    @Test
    void tooManyPagesIsUnreadable() {
        assertEquals(FileIngestionResult.ErrorCode.UNREADABLE,
                FileIngestionResult.ErrorCode.classify("ERROR: too many pages (601)", false));
        assertEquals(FileIngestionResult.ErrorCode.UNREADABLE,
                FileIngestionResult.ErrorCode.classify("error: too many pages (1200)", true));
    }

    @Test
    void unrelatedErrorStaysOther() {
        assertEquals(FileIngestionResult.ErrorCode.OTHER,
                FileIngestionResult.ErrorCode.classify("ERROR: something else", false));
    }
}
