package com.financeos.gmail.classify.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.User;
import com.financeos.gmail.classify.ClassificationResult;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailMessage;
import com.google.api.services.gmail.Gmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassifierEvalRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final EmailClassifier classifier = new EmailClassifier();

    // --- masking ---

    @Test
    void masksRunsOfFourOrMoreDigitsKeepingLength() {
        assertThat(ClassifierEvalRunner.mask("card 1234 and 123 and 123456789 ok")).isEqualTo("card #### and 123 and ######### ok");
    }

    @Test
    void masksEmailAddresses() {
        String masked = ClassifierEvalRunner.mask("write to john.doe+x@example.co.in now");
        assertThat(masked).doesNotContain("john").doesNotContain("@").contains("now");
    }

    @Test
    void maskHandlesNull() {
        assertThat(ClassifierEvalRunner.mask(null)).isEmpty();
    }

    // --- query building ---

    @Test
    void shortListGivesOneQueryWithDaysAndLowercasedDedupedTokens() {
        List<String> q = ClassifierEvalRunner.buildQueries(Set.of("hdfcbank.net"), List.of("HDFCBANK.net", "kotak.com"),
                List.of("Alerts@Bank.com", "alerts@bank.com"), 30);
        assertThat(q).hasSize(1);
        assertThat(q.get(0)).startsWith("newer_than:30d from:(").endsWith(")");
        assertThat(q.get(0).split("hdfcbank\\.net", -1)).hasSize(2);
        assertThat(q.get(0).split("alerts@bank\\.com", -1)).hasSize(2);
        assertThat(q.get(0)).contains("kotak.com");
    }

    @Test
    void longListIsChunkedAndEveryTokenAppearsOnce() {
        Set<String> domains = new LinkedHashSet<>();
        for (int i = 0; i < 80; i++) {
            domains.add("bank-number-" + i + ".example.com");
        }
        List<String> q = ClassifierEvalRunner.buildQueries(domains, List.of(), List.of(), 365);
        assertThat(q.size()).isGreaterThan(1);
        String all = String.join(" ", q);
        for (String d : domains) {
            assertThat(all.split(java.util.regex.Pattern.quote(d) + "(?!\\d)", -1)).hasSize(2);
        }
        for (String query : q) {
            String group = query.substring(query.indexOf('(') + 1, query.length() - 1);
            assertThat(group.length()).isLessThanOrEqualTo(ClassifierEvalRunner.MAX_FROM_GROUP_CHARS);
        }
    }

    @Test
    void noTokensGivesNoQueries() {
        assertThat(ClassifierEvalRunner.buildQueries(Set.of(), List.of(), List.of(), 10)).isEmpty();
    }

    // --- JSON lines ---

    private static GmailMessage message(String id, String from, String subject, String body, List<GmailAttachment> atts) {
        return new GmailMessage(id, Instant.parse("2026-03-04T05:06:07Z"), from, subject, "", body, null, atts);
    }

    @Test
    void normalMessageLineHasExpectedShape() throws Exception {
        GmailMessage m = message("m1", "HDFC Bank <Alerts@hdfcbank.net>", "Alert",
                "Rs. 500.00 has been debited from account XX1234567 to someone@x.com on 01-01-2026.", List.of());
        ClassificationResult r = classifier.classify(m);
        JsonNode j = MAPPER.readTree(ClassifierEvalRunner.toLine(m, r, false, MAPPER));
        assertThat(j.get("id").asText()).isEqualTo("m1");
        assertThat(j.get("internalDate").asText()).isEqualTo("2026-03-04T05:06:07Z");
        assertThat(j.get("from").asText()).isEqualTo("alerts@hdfcbank.net");
        assertThat(j.get("fromDomain").asText()).isEqualTo("hdfcbank.net");
        assertThat(j.get("subject").asText()).isEqualTo("Alert");
        assertThat(j.get("attachments").isArray()).isTrue();
        assertThat(j.get("attachments")).isEmpty();
        assertThat(j.get("pipelineStatementBranch").asBoolean()).isFalse();
        assertThat(j.get("kind").asText()).isEqualTo(r.kind().name());
        assertThat(j.get("subtype").asText()).isEqualTo(r.subtype().name());
        assertThat(j.get("confidence").asText()).isEqualTo(r.confidence().name());
        assertThat(j.get("signals").isArray()).isTrue();
        assertThat(j.has("bank")).isTrue();
        String text = j.get("text").asText();
        assertThat(text).doesNotContain("1234567").doesNotContain("someone@x.com").contains("#######");
        assertThat(text.length()).isLessThanOrEqualTo(700);
    }

    @Test
    void textIsCappedAt700Chars() throws Exception {
        GmailMessage m = message("m2", "a@b.com", "s", "word ".repeat(500), List.of());
        JsonNode j = MAPPER.readTree(ClassifierEvalRunner.toLine(m, classifier.classify(m), false, MAPPER));
        assertThat(j.get("text").asText()).hasSize(700);
    }

    @Test
    void attachmentMessageListsNamesAndTypesWithoutContent() throws Exception {
        GmailAttachment att = new GmailAttachment("aid", "stmt.pdf", "application/pdf", new byte[]{1, 2, 3});
        GmailMessage m = message("m3", "x@sbicard.com", "Your statement", "see attached", List.of(att));
        String line = ClassifierEvalRunner.toLine(m, classifier.classify(m), true, MAPPER);
        JsonNode j = MAPPER.readTree(line);
        assertThat(j.get("pipelineStatementBranch").asBoolean()).isTrue();
        assertThat(j.get("attachments")).hasSize(1);
        assertThat(j.get("attachments").get(0).get("filename").asText()).isEqualTo("stmt.pdf");
        assertThat(j.get("attachments").get(0).get("mimeType").asText()).isEqualTo("application/pdf");
        assertThat(j.get("attachments").get(0).size()).isEqualTo(2);
        assertThat(line).doesNotContain("aid");
        assertThat(line).doesNotContain("\n");
    }

    @Test
    void errorLineHasIdAndError() throws Exception {
        JsonNode j = MAPPER.readTree(ClassifierEvalRunner.errorLine("m9", new IllegalStateException("boom"), MAPPER));
        assertThat(j.get("id").asText()).isEqualTo("m9");
        assertThat(j.get("error").asText()).contains("IllegalStateException").contains("boom");
        assertThat(j.has("kind")).isFalse();
    }

    // --- run loop with a mocked GmailEngine ---

    private ClassifierEvalRunner runner(GmailEngine engine, GmailConnection connection, String out, int max) {
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        when(connections.findAll()).thenReturn(List.of(connection));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        GmailSender sender = new GmailSender();
        sender.setSenderAddress("custom@mybank.com");
        when(senders.findByUserId(any())).thenReturn(List.of(sender));
        return new ClassifierEvalRunner(engine, classifier, connections, senders, MAPPER, null, out, 30, max, "", 0);
    }

    private static GmailConnection connection() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        GmailConnection c = mock(GmailConnection.class);
        when(c.getUser()).thenReturn(user);
        when(c.getId()).thenReturn(UUID.randomUUID());
        return c;
    }

    @Test
    void runWritesOneLinePerUniqueIdAndContinuesPastErrors(@TempDir Path dir) throws Exception {
        GmailEngine engine = mock(GmailEngine.class);
        GmailConnection c = connection();
        Gmail service = mock(Gmail.class);
        when(engine.createService(c)).thenReturn(service);
        // Same id returned by every chunk: must be deduped.
        when(engine.listMessageIds(eq(c), anyString())).thenReturn(List.of("a", "b"));
        when(engine.fetchMessageDetails(service, "a")).thenReturn(message("a", "x@hdfcbank.net", "Hi", "hello", List.of()));
        when(engine.fetchMessageDetails(service, "b")).thenThrow(new RuntimeException("bad"));
        Path out = dir.resolve("sub/out.jsonl");

        ClassifierEvalRunner.Summary s = runner(engine, c, out.toString(), 100).execute();

        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(MAPPER.readTree(lines.get(0)).get("id").asText()).isEqualTo("a");
        assertThat(MAPPER.readTree(lines.get(1)).has("error")).isTrue();
        assertThat(s.total).isEqualTo(2);
        assertThat(s.errors).isEqualTo(1);
        assertThat(s.byDomain).containsEntry("hdfcbank.net", 1);
    }

    @Test
    void authFailureOnFetchStopsTheConnection(@TempDir Path dir) throws Exception {
        GmailEngine engine = mock(GmailEngine.class);
        GmailConnection c = connection();
        Gmail service = mock(Gmail.class);
        when(engine.createService(c)).thenReturn(service);
        when(engine.listMessageIds(eq(c), anyString())).thenReturn(List.of("a", "b"));
        when(engine.fetchMessageDetails(service, "a")).thenThrow(new GmailEngineException(GmailError.AUTH_ERROR, "invalid_grant"));
        Path out = dir.resolve("out.jsonl");

        ClassifierEvalRunner.Summary s = runner(engine, c, out.toString(), 100).execute();

        assertThat(Files.readAllLines(out)).isEmpty();
        assertThat(s.total).isZero();
    }

    @Test
    void authFailureOnListStopsTheConnection(@TempDir Path dir) throws Exception {
        GmailEngine engine = mock(GmailEngine.class);
        GmailConnection c = connection();
        when(engine.listMessageIds(eq(c), anyString())).thenThrow(new GmailEngineException(GmailError.AUTH_ERROR, "invalid_grant"));
        Path out = dir.resolve("out.jsonl");

        ClassifierEvalRunner.Summary s = runner(engine, c, out.toString(), 100).execute();

        assertThat(Files.readAllLines(out)).isEmpty();
        assertThat(s.total).isZero();
    }

    @Test
    void maxCapsMessagesPerConnection(@TempDir Path dir) throws Exception {
        GmailEngine engine = mock(GmailEngine.class);
        GmailConnection c = connection();
        Gmail service = mock(Gmail.class);
        when(engine.createService(c)).thenReturn(service);
        when(engine.listMessageIds(eq(c), anyString())).thenReturn(List.of("a", "b", "c"));
        when(engine.fetchMessageDetails(eq(service), anyString())).thenReturn(message("a", "x@y.com", "s", "b", List.of()));
        Path out = dir.resolve("out.jsonl");

        runner(engine, c, out.toString(), 2).execute();

        assertThat(Files.readAllLines(out)).hasSize(2);
    }
}
