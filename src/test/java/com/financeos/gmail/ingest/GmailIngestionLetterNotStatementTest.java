package com.financeos.gmail.ingest;

import com.financeos.gmail.domain.GmailProcessedMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A bank letter with a PDF never reaches the statement parser; real statement mails keep that path. */
class GmailIngestionLetterNotStatementTest {

    private static GmailProcessedMessage verdict(String kind, String subtype) {
        GmailProcessedMessage g = new GmailProcessedMessage();
        g.setClassifiedKind(kind);
        g.setClassifiedSubtype(subtype);
        return g;
    }

    private static GmailProcessedMessage verdict(String kind, String subtype, String confidence) {
        GmailProcessedMessage g = verdict(kind, subtype);
        g.setClassifiedConfidence(confidence);
        return g;
    }

    @Test
    void documentSkipsWhateverTheKind() {
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "DOCUMENT", "MEDIUM"))).isTrue();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("STATEMENT", "DOCUMENT", "LOW"))).isTrue();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict(null, "DOCUMENT"))).isTrue();
    }

    @Test
    void serviceSkipsOnlyAtMediumOrHigherConfidence() {
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "SERVICE", "MEDIUM"))).isTrue();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "SERVICE", "HIGH"))).isTrue();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "SERVICE", "LOW"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "SERVICE"))).isFalse();
    }

    @Test
    void statementMailsAndUncertainAnswersKeepTheStatementPath() {
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("STATEMENT", "CARD_STATEMENT", "HIGH"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "PROMOTION", "HIGH"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "BILL_DUE", "HIGH"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "STATEMENT_NOTICE", "HIGH"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict("OTHER", "UNKNOWN", "HIGH"))).isFalse();
        assertThat(GmailIngestionService.isLetterNotStatement(verdict(null, null))).isFalse();
    }
}
