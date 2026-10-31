package com.financeos.gmail.classify.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.extract.TransactionExtractor;
import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierEvalExtractOutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GmailMessage message(String id, String from, String subject, String body) {
        return new GmailMessage(id, Instant.parse("2026-03-10T05:30:00Z"), from, subject, "snip", body, null, List.of());
    }

    @Test
    void replayWritesOneExtractLinePerAlertOnly(@TempDir Path dir) throws Exception {
        GmailMessage alert = message("a1", "Some Bank <alerts@somebank.example>", "Transaction alert",
                "INR 2,500.00 has been debited from your A/c XX1234 on 09-03-2026 towards SAMPLE SHOP.");
        GmailMessage otp = message("o1", "Some Bank <alerts@somebank.example>", "Your OTP", "Your OTP is 123456. Do not share it.");
        Path dump = dir.resolve("dump.jsonl");
        Path out = dir.resolve("out.jsonl");
        Path extractOut = dir.resolve("extract.jsonl");
        Files.writeString(dump, ClassifierEvalRunner.dumpLine(alert, MAPPER) + "\n" + ClassifierEvalRunner.dumpLine(otp, MAPPER) + "\n",
                StandardCharsets.UTF_8);

        ClassifierEvalRunner.replay(dump, out, new EmailClassifier(), MAPPER, new TransactionExtractor(), extractOut);

        List<String> lines = Files.readAllLines(extractOut);
        assertThat(lines).hasSize(1);
        JsonNode n = MAPPER.readTree(lines.get(0));
        assertThat(n.fieldNames()).toIterable().containsExactly("id", "kind", "tier", "templateId", "amount", "currency",
                "direction", "date", "counterparty", "last4", "confidence");
        assertThat(n.get("id").asText()).isEqualTo("a1");
        assertThat(n.get("kind").asText()).isEqualTo("ALERT");
        assertThat(n.get("tier").asText()).isEqualTo("GENERIC");
        assertThat(n.get("amount").asText()).isEqualTo("2500.00");
        assertThat(n.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(n.get("date").asText()).isEqualTo("2026-03-09");
        assertThat(n.get("last4").asText()).isEqualTo("1234");
        assertThat(Files.readAllLines(out)).hasSize(2);
    }

    @Test
    void noExtractOutMeansNoExtractFile(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("dump.jsonl");
        Files.writeString(dump, ClassifierEvalRunner.dumpLine(message("a1", "x@y.example", "Alert", "INR 5 debited"), MAPPER) + "\n");
        ClassifierEvalRunner.replay(dump, dir.resolve("out.jsonl"), new EmailClassifier(), MAPPER, new TransactionExtractor(), null);
        assertThat(dir.resolve("extract.jsonl")).doesNotExist();
    }
}
