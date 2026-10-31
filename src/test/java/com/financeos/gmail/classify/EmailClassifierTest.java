package com.financeos.gmail.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.BankCatalog;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EmailClassifierTest {

    private static final String[] FIXTURE_FILES = {
            "hdfc.json", "icici.json", "axis.json", "sbi.json", "other_banks.json",
            "statements.json", "negatives.json", "others.json", "html.json", "labelled.json", "review_fixes.json", "inbox_round1.json", "inbox_round2.json", "inbox_round3.json", "inbox_round4.json", "classifier_v3.json"};

    private final EmailClassifier classifier = new EmailClassifier();
    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> fixtures() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (String file : FIXTURE_FILES) {
            try (InputStream in = getClass().getResourceAsStream("/gmail/classifier/" + file)) {
                JsonNode cases = mapper.readTree(in);
                for (JsonNode c : cases) {
                    tests.add(DynamicTest.dynamicTest(file + ": " + c.get("name").asText(), () -> check(c)));
                }
            }
        }
        return tests.stream();
    }

    private void check(JsonNode c) {
        ClassificationResult r = classifier.classify(message(c));
        String why = c.get("name").asText() + " " + r.signals();
        assertThat(r.kind()).as("kind %s", why).isEqualTo(EmailKind.valueOf(c.get("kind").asText()));
        assertThat(r.subtype()).as("subtype %s", why).isEqualTo(EmailSubtype.valueOf(c.get("subtype").asText()));
        assertThat(r.confidence()).as("confidence %s", why).isEqualTo(ClassifierConfidence.valueOf(c.get("confidence").asText()));
        assertThat(r.bankId()).as("bank").isEqualTo(c.has("bank") ? c.get("bank").asText() : null);
        assertThat(r.version()).isEqualTo(EmailClassifier.VERSION);
        assertThat(r.subtype().kind()).isEqualTo(r.kind());
    }

    private static GmailMessage message(JsonNode c) {
        List<GmailAttachment> atts = new ArrayList<>();
        if (c.has("attachments")) {
            for (JsonNode a : c.get("attachments")) {
                atts.add(new GmailAttachment("att-" + atts.size(), a.get("filename").asText(), a.get("mimeType").asText(), new byte[0]));
            }
        }
        return new GmailMessage("m1", Instant.now(), text(c, "from"), text(c, "subject"), null,
                text(c, "bodyText"), text(c, "bodyHtml"), atts);
    }

    private static String text(JsonNode c, String field) {
        return c.has(field) ? c.get(field).asText() : null;
    }

    @Test
    void nullMessageAndNullFieldsNeverThrow() {
        assertThat(classifier.classify(null).subtype()).isEqualTo(EmailSubtype.UNKNOWN);
        ClassificationResult r = classifier.classify(new GmailMessage(null, null, null, null, null, null, null, null));
        assertThat(r.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.UNKNOWN);
        assertThat(r.confidence()).isEqualTo(ClassifierConfidence.LOW);
    }

    @Test
    void nullAttachmentEntriesAndNullAttachmentFieldsAreIgnored() {
        List<GmailAttachment> atts = new ArrayList<>();
        atts.add(null);
        atts.add(new GmailAttachment("a", null, null, null));
        ClassificationResult r = classifier.classify(new GmailMessage("m", null, "a@hdfcbank.net", "Hello", null, "hi", null, atts));
        assertThat(r.subtype()).isEqualTo(EmailSubtype.UNKNOWN);
    }

    @Test
    void emptyMessageIsUnknownLow() {
        ClassificationResult r = classifier.classify(new GmailMessage("m", null, "", "", "", "", "", List.of()));
        assertThat(r.subtype()).isEqualTo(EmailSubtype.UNKNOWN);
        assertThat(r.confidence()).isEqualTo(ClassifierConfidence.LOW);
        assertThat(r.bankId()).isNull();
    }

    @Test
    void hugeBodyFinishesFastAndStillClassifiesFromTheStart() {
        String alert = "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards CAFE. ";
        String filler = "lorem ipsum dolor sit amet ".repeat(40_000);
        long start = System.nanoTime();
        ClassificationResult r = classifier.classify(new GmailMessage("m", null, "a@hdfcbank.net", "Alert", null, alert + filler, null, List.of()));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
        assertThat(r.subtype()).isEqualTo(EmailSubtype.DEBIT);
    }

    @Test
    void adversarialHtmlAndPunctuationRunInLinearTime() {
        String html = "<style".repeat(30_000) + "<<<<<<".repeat(30_000) + "&#".repeat(30_000);
        String digits = ("OTP " + "1 ".repeat(30)).repeat(2_000) + "INR 1".repeat(10_000);
        long start = System.nanoTime();
        classifier.classify(new GmailMessage("m", null, "a@x.example", "s", null, digits, html, List.of()));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void referenceAndPercentPatternsStayFastOnLongRunsOfSymbols() {
        String[] runs = {"\u2022".repeat(20_000), "1".repeat(20_000), "*".repeat(20_000), "x".repeat(20_000), "9%".repeat(10_000)};
        // Warm the JIT on the same inputs: a slow CI runner's cold first pass is not what this guards against.
        for (String run : runs) {
            classifier.classify(new GmailMessage("w", null, "a@x.example", "s", null, run + " INR 500 spent", null, List.of()));
        }
        // Best of three against a loose bound: CI runs test forks in parallel, so one timing can stall on a busy CPU.
        // Catastrophic backtracking on 20k chars takes seconds or never ends; a healthy run takes ~20 ms.
        for (String run : runs) {
            long best = Long.MAX_VALUE;
            for (int attempt = 0; attempt < 3; attempt++) {
                long start = System.nanoTime();
                classifier.classify(new GmailMessage("m", null, "a@x.example", "s", null, run + " INR 500 spent", null, List.of()));
                best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
            }
            assertThat(best).as("run of %s", run.substring(0, 1)).isLessThan(1000);
        }
    }

    @Test
    void signalsNameTheDecisionWithoutMessageText() {
        ClassificationResult r = classifier.classify(new GmailMessage("m", null, "HDFC <alerts@hdfcbank.net>", "Alert", null,
                "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards BLUE LEAF CAFE.", null, List.of()));
        assertThat(r.signals()).contains("bank:hdfc", "amt", "verb:debited", "ref:card");
        assertThat(String.join(",", r.signals())).doesNotContain("725").doesNotContain("BLUE");
    }

    @Test
    void statementSignalsCarryAttachmentKind() {
        ClassificationResult r = classifier.classify(new GmailMessage("m", null, "a@x.example", "Statement", null, "see attached", null,
                List.of(new GmailAttachment("a", "s.pdf", "application/pdf", null))));
        assertThat(r.signals()).contains("att:pdf", "kw:statement");
    }

    @Test
    void signalsJoinedTruncates() {
        ClassificationResult r = new ClassificationResult(EmailKind.OTHER, EmailSubtype.UNKNOWN, ClassifierConfidence.LOW, null,
                List.of("aaaaaaaaaa", "bbbbbbbbbb"), 1);
        assertThat(r.signalsJoined(500)).isEqualTo("aaaaaaaaaa,bbbbbbbbbb");
        assertThat(r.signalsJoined(15)).isEqualTo("aaaaaaaaaa,bbbb");
    }

    @Test
    void everySubtypeBelongsToItsDocumentedKind() {
        assertThat(EmailSubtype.DEBIT.kind()).isEqualTo(EmailKind.ALERT);
        assertThat(EmailSubtype.REVERSAL.kind()).isEqualTo(EmailKind.ALERT);
        assertThat(EmailSubtype.CARD_STATEMENT.kind()).isEqualTo(EmailKind.STATEMENT);
        assertThat(EmailSubtype.STATEMENT.kind()).isEqualTo(EmailKind.STATEMENT);
        assertThat(EmailSubtype.OTP.kind()).isEqualTo(EmailKind.OTHER);
        assertThat(EmailSubtype.UNKNOWN.kind()).isEqualTo(EmailKind.OTHER);
    }

    @Test
    void fixturesNameOnlyCatalogBanks() throws Exception {
        for (String file : FIXTURE_FILES) {
            try (InputStream in = getClass().getResourceAsStream("/gmail/classifier/" + file)) {
                for (JsonNode c : mapper.readTree(in)) {
                    if (c.has("bank")) {
                        assertThat(BankCatalog.isKnown(c.get("bank").asText())).as(c.get("name").asText()).isTrue();
                    }
                }
            }
        }
    }
}
