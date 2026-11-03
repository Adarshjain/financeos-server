package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.accountdetect.scan.MailSource;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A made-up mailbox (a held-out corpus file, all names and numbers invented) served as Gmail would: listing newest
 * first per sender token, headers, full bodies and statement PDFs (protected ones encrypted, never opened).
 */
class FixtureMailSource implements MailSource {

    static final Path DEV = Path.of("src/test/resources/accountdetect/heldout/dev");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern ADDR = Pattern.compile("@([A-Za-z0-9.-]+)");

    record Mail(String id, String from, Instant date, String subject, String body, List<Att> attachments) {}

    record Att(String attachmentId, String filename, boolean isProtected, String pdfText) {}

    final List<Mail> mails = new ArrayList<>();
    final List<Instant> listedAfter = new CopyOnWriteArrayList<>();
    final List<String> metadataRequested = new CopyOnWriteArrayList<>();
    final List<String> fullRequested = new CopyOnWriteArrayList<>();
    final List<String> attachmentRequested = new CopyOnWriteArrayList<>();
    /** "metadata" or "full": throw an auth error there (an expired Gmail grant). */
    String failAuthAt;
    private final Map<String, byte[]> pdfCache = new HashMap<>();

    static List<Mail> load(String file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(DEV.resolve(file).toFile());
        List<Mail> out = new ArrayList<>();
        for (JsonNode m : root.path("mails")) {
            String id = m.path("id").asText();
            List<Att> atts = new ArrayList<>();
            int i = 0;
            for (JsonNode a : m.path("attachments")) {
                atts.add(new Att("att-" + id + "-" + (i++), a.path("filename").asText(""), a.path("protected").asBoolean(false),
                        a.hasNonNull("pdfText") ? a.path("pdfText").asText() : null));
            }
            LocalDate d = LocalDate.parse(m.path("date").asText().substring(0, 10));
            out.add(new Mail(id, m.path("from").asText(), d.atTime(12, 0).atZone(IST).toInstant(), m.path("subject").asText(""),
                    m.path("body").asText(""), atts));
        }
        return out;
    }

    FixtureMailSource(List<Mail> mails) {
        this.mails.addAll(mails);
    }

    static FixtureMailSource of(String file, Predicate<Mail> keep) throws IOException {
        return new FixtureMailSource(load(file).stream().filter(keep).toList());
    }

    private Mail mail(String id) {
        return mails.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    static String domainOf(String from) {
        Matcher m = ADDR.matcher(from == null ? "" : from);
        String d = "";
        while (m.find()) d = m.group(1);
        return d.toLowerCase(Locale.ROOT);
    }

    @Override
    public Listing list(Collection<String> tokens, Instant after) {
        listedAfter.add(after == null ? Instant.EPOCH : after);
        List<String> ids = new ArrayList<>();
        int calls = 0;
        for (String t : tokens) {
            calls++;
            mails.stream()
                    .filter(m -> m.from().toLowerCase(Locale.ROOT).contains(t))
                    .filter(m -> after == null || !m.date().isBefore(after))
                    .sorted(Comparator.comparing(Mail::date).reversed().thenComparing(Mail::id))
                    .forEach(m -> {
                        if (!ids.contains(m.id())) ids.add(m.id());
                    });
        }
        return new Listing(ids, calls);
    }

    @Override
    public Map<String, MailFeature> metadata(List<String> ids, Consumer<String> out) {
        if ("metadata".equals(failAuthAt)) throw new GmailEngineException(GmailError.AUTH_ERROR, "Google rejected the refresh token (invalid_grant)");
        metadataRequested.addAll(ids);
        Map<String, MailFeature> r = new LinkedHashMap<>();
        for (String id : ids) {
            Mail m = mail(id);
            if (m == null) continue;
            String snippet = m.body().replaceAll("\\s+", " ").trim();
            if (snippet.length() > 150) snippet = snippet.substring(0, 150);
            r.put(id, new MailFeature(domainOf(m.from()), m.date(), m.subject(), snippet, null, List.of()));
        }
        out.accept("  metadata retried ids=0");
        return r;
    }

    @Override
    public Map<String, GmailMessage> full(List<String> ids, Consumer<String> out) {
        if ("full".equals(failAuthAt) && !ids.isEmpty()) throw new GmailEngineException(GmailError.AUTH_ERROR, "Google rejected authentication (401)");
        fullRequested.addAll(ids);
        Map<String, GmailMessage> r = new LinkedHashMap<>();
        for (String id : ids) {
            Mail m = mail(id);
            if (m == null) continue;
            List<GmailAttachment> atts = m.attachments().stream()
                    .map(a -> new GmailAttachment(a.attachmentId(), a.filename(), a.filename().toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" : "application/octet-stream", null))
                    .toList();
            String snippet = m.body().replaceAll("\\s+", " ").trim();
            r.put(id, new GmailMessage(id, m.date(), m.from(), m.subject(), snippet.length() > 150 ? snippet.substring(0, 150) : snippet, m.body(), "", atts));
        }
        out.accept("  full retries=0 failed=0 gone=0");
        return r;
    }

    @Override
    public Attachments attachments() {
        return (messageId, attachmentId) -> {
            attachmentRequested.add(attachmentId);
            Mail m = mail(messageId);
            if (m == null) return null;
            for (Att a : m.attachments()) {
                if (!a.attachmentId().equals(attachmentId)) continue;
                synchronized (pdfCache) {
                    return pdfCache.computeIfAbsent(attachmentId, k -> {
                        try {
                            return pdf(a.pdfText() == null ? "" : a.pdfText(), a.isProtected() || a.pdfText() == null);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
            }
            return null;
        };
    }

    static byte[] pdf(String text, boolean encrypted) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> lines = List.of(text.split("\\r?\\n"));
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < Math.max(1, lines.size()); i += 45) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 9);
                    cs.newLineAtOffset(30, 760);
                    for (String l : lines.subList(Math.min(i, lines.size()), Math.min(lines.size(), i + 45))) {
                        StringBuilder sb = new StringBuilder();
                        for (char ch : l.toCharArray()) sb.append(ch >= 32 && ch < 127 ? ch : ' ');
                        cs.showText(sb.toString());
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            if (encrypted) {
                StandardProtectionPolicy p = new StandardProtectionPolicy("owner-made-up", "user-made-up", new AccessPermission());
                p.setEncryptionKeyLength(128);
                doc.protect(p);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
