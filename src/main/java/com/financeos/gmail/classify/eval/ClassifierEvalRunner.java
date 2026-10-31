package com.financeos.gmail.classify.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.classify.ClassificationResult;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.classify.EmailText;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailMessage;
import com.google.api.services.gmail.Gmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, dev-only evaluation of {@link EmailClassifier} over the owner's own Gmail. Reads mail through
 * {@link GmailEngine}, classifies, and writes one JSONL line per message. Writes nothing to the DB,
 * never calls AI, never creates transactions. Active only under the {@code classifier-eval} profile.
 */
@Component
@Profile("classifier-eval")
public class ClassifierEvalRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClassifierEvalRunner.class);

    static final int TEXT_CHARS = 700;
    static final int MAX_FROM_GROUP_CHARS = 400;
    private static final long THROTTLE_MS = 100;

    /** Common Indian bank/card domains outside the catalog, so we see what the classifier does on them. */
    static final List<String> EXTRA_DOMAINS = List.of("kotak.com", "kotak.bank.in", "onecard.app", "jupiter.money",
            "fi.money", "rblbank.com", "dbs.com", "sc.com", "citi.com", "cred.club", "paytm.com", "phonepe.com",
            "amazonpay.in");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{4,}");

    private final GmailEngine gmailEngine;
    private final EmailClassifier classifier;
    private final GmailConnectionRepository connectionRepository;
    private final GmailSenderRepository senderRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext context;
    private final String out;
    private final int days;
    private final int max;
    private final String connectionId;
    private final long throttleMs;
    private final String dump;
    private final String replay;

    @org.springframework.beans.factory.annotation.Autowired
    public ClassifierEvalRunner(GmailEngine gmailEngine,
                                EmailClassifier classifier,
                                GmailConnectionRepository connectionRepository,
                                GmailSenderRepository senderRepository,
                                ObjectMapper objectMapper,
                                ApplicationContext context,
                                @Value("${financeos.classifier-eval.out:}") String out,
                                @Value("${financeos.classifier-eval.days:365}") int days,
                                @Value("${financeos.classifier-eval.max:3000}") int max,
                                @Value("${financeos.classifier-eval.connection-id:}") String connectionId,
                                @Value("${financeos.classifier-eval.dump:}") String dump,
                                @Value("${financeos.classifier-eval.replay:}") String replay) {
        this(gmailEngine, classifier, connectionRepository, senderRepository, objectMapper, context, out, days, max,
                connectionId, THROTTLE_MS, dump, replay);
    }

    ClassifierEvalRunner(GmailEngine gmailEngine, EmailClassifier classifier,
                         GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                         ObjectMapper objectMapper, ApplicationContext context, String out, int days, int max,
                         String connectionId, long throttleMs) {
        this(gmailEngine, classifier, connectionRepository, senderRepository, objectMapper, context, out, days, max,
                connectionId, throttleMs, "", "");
    }

    ClassifierEvalRunner(GmailEngine gmailEngine, EmailClassifier classifier,
                         GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                         ObjectMapper objectMapper, ApplicationContext context, String out, int days, int max,
                         String connectionId, long throttleMs, String dump, String replay) {
        this.dump = dump;
        this.replay = replay;
        this.gmailEngine = gmailEngine;
        this.classifier = classifier;
        this.connectionRepository = connectionRepository;
        this.senderRepository = senderRepository;
        this.objectMapper = objectMapper;
        this.context = context;
        this.out = out;
        this.days = days;
        this.max = max;
        this.connectionId = connectionId;
        this.throttleMs = throttleMs;
    }

    @Override
    public void run(String... args) {
        final int[] code = {0};
        try {
            execute();
        } catch (Exception e) {
            log.error("Classifier eval failed: {}", e.getMessage(), e);
            code[0] = 1;
        }
        System.exit(SpringApplication.exit(context, () -> code[0]));
    }

    /** Runs the whole evaluation and returns the totals. Separate from {@link #run} so it never exits the JVM. */
    Summary execute() throws IOException {
        if (out == null || out.isBlank()) {
            throw new IllegalArgumentException("financeos.classifier-eval.out is required");
        }
        if (replay != null && !replay.isBlank()) {
            return replay(Path.of(replay), Path.of(out), classifier, objectMapper);
        }
        List<GmailConnection> connections = selectConnections();
        Summary summary = new Summary();
        Path path = Path.of(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        BufferedWriter dumpWriter = null;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            if (dump != null && !dump.isBlank()) {
                Path dumpPath = Path.of(dump);
                if (dumpPath.getParent() != null) {
                    Files.createDirectories(dumpPath.getParent());
                }
                log.warn("Writing raw, UNMASKED mail to {}: it must stay local and never be committed or shared", dumpPath);
                dumpWriter = Files.newBufferedWriter(dumpPath, StandardCharsets.UTF_8);
            }
            for (GmailConnection connection : connections) {
                processConnection(connection, writer, dumpWriter, summary);
            }
        } finally {
            if (dumpWriter != null) {
                dumpWriter.close();
            }
        }
        log.info("Classifier eval wrote {} message(s) ({} error(s)) to {}", summary.total, summary.errors, path);
        log.info("By kind/subtype: {}", summary.byKind);
        log.info("By from domain: {}", summary.byDomain);
        return summary;
    }

    private List<GmailConnection> selectConnections() {
        List<GmailConnection> all = connectionRepository.findAll();
        if (connectionId == null || connectionId.isBlank()) {
            return all;
        }
        return all.stream().filter(c -> c.getId() != null && c.getId().toString().equals(connectionId.trim())).toList();
    }

    private void processConnection(GmailConnection connection, BufferedWriter writer, BufferedWriter dumpWriter,
                                   Summary summary) throws IOException {
        List<String> senders = senderRepository.findByUserId(connection.getUser().getId()).stream()
                .map(GmailSender::getSenderAddress).toList();
        List<String> queries = buildQueries(BankSenderDomains.domains(), EXTRA_DOMAINS, senders, days);
        Set<String> ids = new LinkedHashSet<>();
        Gmail service;
        try {
            for (String query : queries) {
                ids.addAll(gmailEngine.listMessageIds(connection, query));
            }
            service = gmailEngine.createService(connection);
        } catch (GmailEngineException e) {
            if (e.getErrorType() == GmailError.AUTH_ERROR) {
                log.warn("Gmail connection {} needs reconnect", connection.getId());
                return;
            }
            throw e;
        } catch (IOException e) {
            if (isAuthFailure(e)) {
                log.warn("Gmail connection {} needs reconnect", connection.getId());
                return;
            }
            throw e;
        }
        log.info("Connection {}: {} message id(s) over {} query(ies)", connection.getId(), ids.size(), queries.size());
        int processed = 0;
        for (String id : ids) {
            if (processed >= max) {
                log.info("Reached max={} for connection {}", max, connection.getId());
                break;
            }
            processed++;
            String line;
            try {
                GmailMessage message = gmailEngine.fetchMessageDetails(service, id);
                ClassificationResult result = classifier.classify(message);
                line = toLine(message, result, GmailIngestionService.hasStatementAttachment(message), objectMapper);
                summary.record(result, fromAddress(message.from()));
                if (dumpWriter != null) {
                    dumpWriter.write(dumpLine(message, objectMapper));
                    dumpWriter.write('\n');
                }
            } catch (GmailEngineException e) {
                if (e.getErrorType() == GmailError.AUTH_ERROR) {
                    log.warn("Gmail connection {} needs reconnect", connection.getId());
                    return;
                }
                line = errorLine(id, e, objectMapper);
                summary.recordError();
            } catch (Exception e) {
                line = errorLine(id, e, objectMapper);
                summary.recordError();
            }
            writer.write(line);
            writer.write('\n');
            sleep();
        }
        writer.flush();
        if (dumpWriter != null) {
            dumpWriter.flush();
        }
    }

    private void sleep() {
        if (throttleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isAuthFailure(IOException e) {
        String m = e.getMessage();
        return m != null && m.toLowerCase(Locale.ROOT).contains("invalid_grant");
    }

    // ---------------------------------------------------------------------------------------------
    // Pure helpers (unit-tested)
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code newer_than:Nd from:(a OR b ...)} queries over catalog domains, extra domains and the user's
     * sender addresses, lowercased and deduped, each {@code from:()} group capped at
     * {@link #MAX_FROM_GROUP_CHARS} characters.
     */
    static List<String> buildQueries(Set<String> catalogDomains, List<String> extraDomains, List<String> senders, int days) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String d : catalogDomains) {
            addToken(tokens, d);
        }
        for (String d : extraDomains) {
            addToken(tokens, d);
        }
        for (String s : senders) {
            addToken(tokens, s);
        }
        List<String> queries = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        for (String token : tokens) {
            if (group.length() > 0 && group.length() + 4 + token.length() > MAX_FROM_GROUP_CHARS) {
                queries.add(query(days, group));
                group.setLength(0);
            }
            if (group.length() > 0) {
                group.append(" OR ");
            }
            group.append(token);
        }
        if (group.length() > 0) {
            queries.add(query(days, group));
        }
        return queries;
    }

    private static void addToken(Set<String> tokens, String raw) {
        if (raw != null && !raw.isBlank()) {
            tokens.add(raw.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String query(int days, CharSequence group) {
        return "newer_than:" + days + "d from:(" + group + ")";
    }

    /** Masks email addresses and every run of 4+ digits (with {@code #} of the same length). */
    static String mask(String text) {
        if (text == null) {
            return "";
        }
        String noEmails = EMAIL.matcher(text).replaceAll("<email>");
        Matcher m = LONG_DIGITS.matcher(noEmails);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("#".repeat(m.end() - m.start())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String fromAddress(String fromHeader) {
        if (fromHeader == null) {
            return "";
        }
        int lt = fromHeader.indexOf('<');
        int gt = fromHeader.indexOf('>');
        String address = lt >= 0 && gt > lt ? fromHeader.substring(lt + 1, gt) : fromHeader;
        return address.trim().toLowerCase(Locale.ROOT);
    }

    static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at >= 0 ? address.substring(at + 1) : "";
    }

    static String toLine(GmailMessage message, ClassificationResult result, boolean pipelineStatementBranch,
                         ObjectMapper mapper) throws JsonProcessingException {
        String address = fromAddress(message.from());
        List<Map<String, String>> attachments = new ArrayList<>();
        if (message.attachments() != null) {
            for (GmailAttachment a : message.attachments()) {
                Map<String, String> att = new LinkedHashMap<>();
                att.put("filename", a.filename());
                att.put("mimeType", a.mimeType());
                attachments.add(att);
            }
        }
        String text = mask(EmailText.of(message).text());
        if (text.length() > TEXT_CHARS) {
            text = text.substring(0, TEXT_CHARS);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", message.messageId());
        line.put("internalDate", message.internalDate() == null ? null : message.internalDate().toString());
        line.put("from", address);
        line.put("fromDomain", domainOf(address));
        line.put("subject", mask(message.subject()));
        line.put("attachments", attachments);
        line.put("pipelineStatementBranch", pipelineStatementBranch);
        line.put("kind", result.kind().name());
        line.put("subtype", result.subtype().name());
        line.put("confidence", result.confidence().name());
        line.put("bank", result.bankId());
        line.put("signals", result.signals());
        line.put("text", text);
        return write(mapper, line);
    }

    /** One raw (unmasked) JSON line per message, for later offline replay. No attachment bytes. */
    static String dumpLine(GmailMessage message, ObjectMapper mapper) throws JsonProcessingException {
        List<Map<String, String>> attachments = new ArrayList<>();
        if (message.attachments() != null) {
            for (GmailAttachment a : message.attachments()) {
                Map<String, String> att = new LinkedHashMap<>();
                att.put("filename", a.filename());
                att.put("mimeType", a.mimeType());
                attachments.add(att);
            }
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", message.messageId());
        line.put("internalDate", message.internalDate() == null ? null : message.internalDate().toString());
        line.put("from", message.from());
        line.put("subject", message.subject());
        line.put("snippet", message.snippet());
        line.put("bodyText", message.bodyText());
        line.put("bodyHtml", message.bodyHtml());
        line.put("attachments", attachments);
        return write(mapper, line);
    }

    /** Rebuilds a message from a {@link #dumpLine} line; attachments come back with null content. */
    static GmailMessage fromDumpLine(String json, ObjectMapper mapper) throws IOException {
        JsonNode n = mapper.readTree(json);
        if (n == null || !n.isObject() || !n.hasNonNull("id")) {
            throw new IllegalArgumentException("not a dump line");
        }
        List<GmailAttachment> attachments = new ArrayList<>();
        JsonNode atts = n.get("attachments");
        if (atts != null && atts.isArray()) {
            for (JsonNode a : atts) {
                attachments.add(new GmailAttachment(null, text(a, "filename"), text(a, "mimeType"), null));
            }
        }
        String date = text(n, "internalDate");
        return new GmailMessage(n.get("id").asText(), date == null ? null : Instant.parse(date), text(n, "from"),
                text(n, "subject"), text(n, "snippet"), text(n, "bodyText"), text(n, "bodyHtml"), attachments);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Classifies every line of a dump and writes the normal masked output. Malformed lines become error lines. */
    static Summary replay(Path dumpPath, Path outPath, EmailClassifier classifier, ObjectMapper mapper)
            throws IOException {
        Summary summary = new Summary();
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        try (BufferedReader reader = Files.newBufferedReader(dumpPath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
            String raw;
            int lineNo = 0;
            while ((raw = reader.readLine()) != null) {
                lineNo++;
                if (raw.isBlank()) {
                    continue;
                }
                String line;
                try {
                    GmailMessage message = fromDumpLine(raw, mapper);
                    ClassificationResult result = classifier.classify(message);
                    line = toLine(message, result, GmailIngestionService.hasStatementAttachment(message), mapper);
                    summary.record(result, fromAddress(message.from()));
                } catch (Exception e) {
                    line = errorLine("line-" + lineNo, e, mapper);
                    summary.recordError();
                }
                writer.write(line);
                writer.write('\n');
            }
        }
        log.info("Classifier replay wrote {} message(s) ({} error(s)) to {}", summary.total, summary.errors, outPath);
        log.info("By kind/subtype: {}", summary.byKind);
        return summary;
    }

    /** Spring-free entry point: {@code ClassifierEvalRunner replay <dump.jsonl> <out.jsonl>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !"replay".equals(args[0])) {
            System.err.println("usage: ClassifierEvalRunner replay <dump.jsonl> <out.jsonl>");
            System.exit(2);
        }
        Summary s = replay(Path.of(args[1]), Path.of(args[2]), new EmailClassifier(), new ObjectMapper());
        System.out.println("replayed " + s.total + " message(s), " + s.errors + " error(s), by kind: " + s.byKind);
    }

    static String errorLine(String id, Exception e, ObjectMapper mapper) throws JsonProcessingException {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", id);
        line.put("error", e.getClass().getSimpleName() + ": " + mask(e.getMessage()));
        return write(mapper, line);
    }

    private static String write(ObjectMapper mapper, Map<String, Object> line) throws JsonProcessingException {
        return mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(line);
    }

    /** Running counts for the end-of-run log. */
    static final class Summary {
        int total;
        int errors;
        final Map<String, Integer> byKind = new TreeMap<>();
        final Map<String, Integer> byDomain = new TreeMap<>();

        void record(ClassificationResult result, String address) {
            total++;
            byKind.merge(result.kind().name() + "/" + result.subtype().name(), 1, Integer::sum);
            byDomain.merge(domainOf(address), 1, Integer::sum);
        }

        void recordError() {
            total++;
            errors++;
        }
    }
}
