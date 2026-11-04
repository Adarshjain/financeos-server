package com.financeos.domain.ingestion;

import com.financeos.domain.ingestion.FileIngestionResult.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileIngestionResultErrorCodeTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";

    @Test
    void lockedFileWithoutAPasswordNeedsOne() {
        assertThat(ErrorCode.classify(LOCKED, false)).isEqualTo(ErrorCode.PASSWORD_REQUIRED);
    }

    @Test
    void lockedFileThatStayedLockedWithAPasswordIsWrongPassword() {
        assertThat(ErrorCode.classify(LOCKED, true)).isEqualTo(ErrorCode.WRONG_PASSWORD);
        assertThat(ErrorCode.classify("ERROR: Excel is password-protected and the password is missing or wrong.", true))
                .isEqualTo(ErrorCode.WRONG_PASSWORD);
    }

    @Test
    void unreadableFilesAreUnreadable() {
        assertThat(ErrorCode.classify("ERROR: failed to read PDF.", false)).isEqualTo(ErrorCode.UNREADABLE);
        assertThat(ErrorCode.classify("ERROR: no text layer found (scanned/image PDF?).", false)).isEqualTo(ErrorCode.UNREADABLE);
        assertThat(ErrorCode.classify("File is empty", false)).isEqualTo(ErrorCode.UNREADABLE);
        assertThat(ErrorCode.classify("ERROR: unsupported file type (expected PDF or Excel)", false)).isEqualTo(ErrorCode.UNREADABLE);
    }

    @Test
    void everythingElseIsOther() {
        assertThat(ErrorCode.classify("ERROR: could not infer a date format for the transaction rows.", false)).isEqualTo(ErrorCode.OTHER);
        assertThat(ErrorCode.classify(null, false)).isEqualTo(ErrorCode.OTHER);
    }

    @Test
    void failedSummaryCarriesTheCodeAndSuccessCarriesNone() {
        assertThat(FileIngestionResult.FileSummary.failed("a.pdf", "x", ErrorCode.OTHER).errorCode()).isEqualTo(ErrorCode.OTHER);
        assertThat(new FileIngestionResult.FileSummary("a.pdf", "SUCCESS", 3, null).errorCode()).isNull();
    }
}
