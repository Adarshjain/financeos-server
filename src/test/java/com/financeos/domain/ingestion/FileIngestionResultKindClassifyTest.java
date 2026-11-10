package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.ingestion.FileIngestionResult.ErrorCode;
import com.financeos.statement.parser.StatementParseException.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Classifying by the typed Kind gives exactly the code the message wording gives, for every message the parser produces. */
class FileIngestionResultKindClassifyTest {

    /** Every message the parser and ingest path throw, with the Kind it is thrown with. */
    private static final Map<String, Kind> MESSAGES = new java.util.LinkedHashMap<>();

    static {
        MESSAGES.put("ERROR: PDF is password-protected and the password is missing or wrong.", Kind.LOCKED);
        MESSAGES.put("ERROR: Excel is password-protected and the password is missing or wrong.", Kind.LOCKED);
        MESSAGES.put("ERROR: unsupported file type (expected PDF or Excel)", Kind.UNSUPPORTED_TYPE);
        MESSAGES.put("ERROR: file too small or empty", Kind.EMPTY_FILE);
        MESSAGES.put("ERROR: too many pages (601)", Kind.TOO_MANY_PAGES);
        MESSAGES.put("ERROR: no text layer found (scanned/image PDF?).", Kind.NO_TEXT_LAYER);
        MESSAGES.put("ERROR: failed to read PDF.", Kind.UNREADABLE);
        MESSAGES.put("ERROR: failed to read PDF text.", Kind.UNREADABLE);
        MESSAGES.put("ERROR: failed to read Excel file.", Kind.UNREADABLE);
        MESSAGES.put("ERROR: failed to read file.", Kind.UNREADABLE);
        MESSAGES.put("ERROR: no date-anchored rows found — cannot locate a transaction table. Run with --debug and share the output.", Kind.NO_TABLE);
        MESSAGES.put("ERROR: could not infer a date format for the transaction rows.", Kind.DATE_FORMAT);
        MESSAGES.put("ERROR: no transaction rows conform to the inferred date format.", Kind.DATE_FORMAT);
        MESSAGES.put("Statement parse failure: boom", Kind.OTHER);
    }

    @Test
    void theKindGivesTheSameCodeAsTheMessageForEveryParserMessage() {
        for (var entry : MESSAGES.entrySet()) {
            for (boolean tried : List.of(false, true)) {
                assertThat(ErrorCode.classify(entry.getValue(), entry.getKey(), tried))
                        .as("%s (%s, password tried: %s)", entry.getKey(), entry.getValue(), tried)
                        .isEqualTo(ErrorCode.classify(entry.getKey(), tried));
            }
        }
    }

    @Test
    void lockedIsPasswordRequiredOrWrongPasswordByWhetherOneWasTried() {
        assertThat(ErrorCode.classify(Kind.LOCKED, "x", false)).isEqualTo(ErrorCode.PASSWORD_REQUIRED);
        assertThat(ErrorCode.classify(Kind.LOCKED, "x", true)).isEqualTo(ErrorCode.WRONG_PASSWORD);
    }

    @Test
    void everyKindThatCannotBeReadIsUnreadableWhateverTheMessage() {
        for (Kind k : List.of(Kind.UNSUPPORTED_TYPE, Kind.EMPTY_FILE, Kind.TOO_MANY_PAGES, Kind.NO_TEXT_LAYER, Kind.UNREADABLE)) {
            assertThat(ErrorCode.classify(k, null, false)).as(k.name()).isEqualTo(ErrorCode.UNREADABLE);
            assertThat(ErrorCode.classify(k, "any wording", true)).as(k.name()).isEqualTo(ErrorCode.UNREADABLE);
        }
    }

    @Test
    void noTableAndDateFormatAreOther() {
        assertThat(ErrorCode.classify(Kind.NO_TABLE, null, false)).isEqualTo(ErrorCode.OTHER);
        assertThat(ErrorCode.classify(Kind.DATE_FORMAT, "x", true)).isEqualTo(ErrorCode.OTHER);
    }

    @Test
    void aNullKindFallsBackToTheMessageRule() {
        assertThat(ErrorCode.classify((Kind) null, "ERROR: PDF is password-protected and the password is missing or wrong.", false))
                .isEqualTo(ErrorCode.PASSWORD_REQUIRED);
        assertThat(ErrorCode.classify((Kind) null, "ERROR: failed to read PDF.", false)).isEqualTo(ErrorCode.UNREADABLE);
        assertThat(ErrorCode.classify((Kind) null, null, false)).isEqualTo(ErrorCode.OTHER);
    }

    @Test
    void anOtherKindFallsBackToTheMessageRuleToo() {
        assertThat(ErrorCode.classify(Kind.OTHER, "File is empty", false)).isEqualTo(ErrorCode.UNREADABLE);
        assertThat(ErrorCode.classify(Kind.OTHER, "ERROR: Excel is password-protected", true)).isEqualTo(ErrorCode.WRONG_PASSWORD);
        assertThat(ErrorCode.classify(Kind.OTHER, "Statement parse failure: x", false)).isEqualTo(ErrorCode.OTHER);
    }
}
