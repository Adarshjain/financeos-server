package com.financeos.gmail.classify.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierEvalReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EmailClassifier classifier = new EmailClassifier();

    private static GmailMessage message(String id, String subject) {
        return new GmailMessage(id, Instant.parse("2026-01-02T03:04:05Z"), "HDFC Bank <alerts@hdfcbank.net>", subject,
                "snip", "Rs.500.00 debited from your card ending 1234", "<p>html</p>",
                List.of(new GmailAttachment("att1", "stmt.pdf", "application/pdf", new byte[]{1, 2, 3})));
    }

    @Test
    void dumpLineHasRawFieldsAndNoAttachmentBytes() throws Exception {
        String line = ClassifierEvalRunner.dumpLine(message("m1", "Alert"), MAPPER);
        JsonNode n = MAPPER.readTree(line);
        assertThat(n.fieldNames()).toIterable().containsExactly("id", "internalDate", "from", "subject", "snippet",
                "bodyText", "bodyHtml", "attachments");
        assertThat(n.get("from").asText()).isEqualTo("HDFC Bank <alerts@hdfcbank.net>");
        assertThat(n.get("bodyText").asText()).contains("1234");
        assertThat(n.get("internalDate").asText()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(n.get("attachments")).hasSize(1);
        assertThat(n.get("attachments").get(0).fieldNames()).toIterable().containsExactly("filename", "mimeType");
        assertThat(line).doesNotContain("att1");
    }

    @Test
    void replayRoundTripMatchesDirectClassification(@TempDir Path dir) throws Exception {
        GmailMessage m = message("m1", "Transaction alert");
        Path dump = dir.resolve("dump.jsonl");
        Path out = dir.resolve("out.jsonl");
        Files.writeString(dump, ClassifierEvalRunner.dumpLine(m, MAPPER) + "\n", StandardCharsets.UTF_8);

        ClassifierEvalRunner.Summary summary = ClassifierEvalRunner.replay(dump, out, classifier, MAPPER);

        String direct = ClassifierEvalRunner.toLine(m, classifier.classify(m), true, MAPPER);
        // direct message has attachment bytes, replayed has none; output never includes bytes so lines match
        assertThat(Files.readAllLines(out)).containsExactly(direct);
        assertThat(summary.total).isEqualTo(1);
        assertThat(summary.errors).isZero();
    }

    @Test
    void replaySkipsMalformedLineAndContinues(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("dump.jsonl");
        Path out = dir.resolve("out.jsonl");
        Files.writeString(dump, ClassifierEvalRunner.dumpLine(message("a", "One"), MAPPER) + "\n"
                + "{not json\n"
                + "\n"
                + ClassifierEvalRunner.dumpLine(message("b", "Two"), MAPPER) + "\n", StandardCharsets.UTF_8);

        ClassifierEvalRunner.Summary summary = ClassifierEvalRunner.replay(dump, out, classifier, MAPPER);

        List<String> lines = Files.readAllLines(out);
        assertThat(lines).hasSize(3);
        assertThat(MAPPER.readTree(lines.get(0)).get("id").asText()).isEqualTo("a");
        assertThat(MAPPER.readTree(lines.get(1)).has("error")).isTrue();
        assertThat(MAPPER.readTree(lines.get(2)).get("id").asText()).isEqualTo("b");
        assertThat(summary.total).isEqualTo(3);
        assertThat(summary.errors).isEqualTo(1);
    }
}
