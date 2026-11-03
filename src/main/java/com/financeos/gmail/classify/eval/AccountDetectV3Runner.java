package com.financeos.gmail.classify.eval;

import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.DetectionReport;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.FeatureCache;
import com.financeos.gmail.accountdetect.FeatureExtractor;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.accountdetect.Stability;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.UnlockSelector;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.MessageGoneException;
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, dev-only runner for the deterministic account detector (v3). Read-only: never writes to the DB, never
 * calls AI, never handles passwords (encrypted statement PDFs are skipped). Mail text stays in memory; the report
 * and cache hold only banks, domains, counts, yyyy-mm, kinds, roles, dictionary variant names, masked last4,
 * hashed ids and timings. Pass {@code --from-cache} to rebuild from the derived-feature cache. Connection id, output
 * folder and statements folder come from ACCOUNT_DETECT_CONNECTION_ID, ACCOUNT_DETECT_OUT_DIR and
 * ACCOUNT_DETECT_STATEMENTS_DIR (see application-account-detect-v3.properties for the run command).
 */
@Component
@Profile("account-detect-v3")
public class AccountDetectV3Runner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountDetectV3Runner.class);
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
    private static final Set<Role> FULL_ROLES = Set.of(Role.STATEMENT, Role.DUE_REMINDER, Role.CARD_SERVICE, Role.ACCOUNT_SERVICE);

    private final GmailEngine engine;
    private final GmailConnectionRepository connectionRepository;
    private final GmailSenderRepository senderRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;
    private final String connectionId;
    private final String outDir;
    private final int batchSize;
    private final double getsPerSecond;
    private final int alertSample;
    private final String statementsDir;
    private final StringBuilder report = new StringBuilder();

    public AccountDetectV3Runner(GmailEngine engine, GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                                 JdbcTemplate jdbc, ApplicationContext context,
                                 @Value("${ACCOUNT_DETECT_CONNECTION_ID:${financeos.account-detect-v3.connection-id:}}") String connectionId,
                                 @Value("${ACCOUNT_DETECT_OUT_DIR:${financeos.account-detect-v3.out-dir:}}") String outDir,
                                 @Value("${financeos.account-detect-v3.batch-size:20}") int batchSize,
                                 @Value("${financeos.account-detect-v3.gets-per-second:45}") double getsPerSecond,
                                 @Value("${financeos.account-detect-v3.alert-sample:12}") int alertSample,
                                 @Value("${ACCOUNT_DETECT_STATEMENTS_DIR:${financeos.account-detect-v3.statements-dir:}}") String statementsDir) {
        this.engine = engine;
        this.connectionRepository = connectionRepository;
        this.senderRepository = senderRepository;
        this.jdbc = jdbc;
        this.context = context;
        this.connectionId = connectionId;
        this.outDir = outDir;
        this.batchSize = batchSize;
        this.getsPerSecond = getsPerSecond;
        this.alertSample = alertSample;
        this.statementsDir = statementsDir;
    }

    @Override
    public void run(String... args) {
        int code = 0;
        boolean fromCache = Arrays.asList(args).contains("--from-cache");
        try {
            execute(fromCache);
        } catch (Exception e) {
            code = 1;
            String m = e.getMessage() == null ? "" : e.getMessage();
            out(m.toLowerCase(Locale.ROOT).contains("invalid_grant") ? "AUTH_FAILED invalid_grant: owner must reconnect Gmail"
                    : "FAILED " + e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : ""));
            log.error("v3 runner failed: {}", e.getClass().getSimpleName());
        }
        write(fromCache ? "report-v3-cache.txt" : "report-v3.txt", report.toString());
        final int c = code;
        System.exit(SpringApplication.exit(context, () -> c));
    }

    private void out(String line) {
        report.append(line).append('\n');
    }

    private void write(String name, String content) {
        try {
            Files.createDirectories(Path.of(outDir));
            Files.writeString(Path.of(outDir).resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("could not write {}", name);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private void execute(boolean fromCache) throws Exception {
        Path cache = Path.of(outDir).resolve("features.tsv");
        List<MailDerived> derived;
        List<StatementContent> statements;
        Fetched fetched = null;
        Map<String, Long> timings = new LinkedHashMap<>();
        long tAll = System.currentTimeMillis();
        if (fromCache) {
            FeatureCache.Loaded l = FeatureCache.read(cache);
            derived = l.mails();
            statements = l.statements();
            out("SOURCE: derived-feature cache (" + derived.size() + " mails, " + statements.size() + " statement-content rows)");
        } else {
            Fetched f = fetch(timings);
            derived = f.derived;
            statements = f.statements;
            fetched = f;
            long t0 = System.currentTimeMillis();
            FeatureCache.write(cache, derived, statements);
            timings.put("cache write", System.currentTimeMillis() - t0);
        }

        long t0 = System.currentTimeMillis();
        Stability.Result r = Stability.detectStable(derived, statements, 42L);
        timings.put("detect + 20 bootstrap resamples", System.currentTimeMillis() - t0);
        t0 = System.currentTimeMillis();
        String checks = DetectionReport.checks(r, derived, statements);
        timings.put("V2 checks", System.currentTimeMillis() - t0);
        timings.put("TOTAL", System.currentTimeMillis() - tAll);

        out("");
        out(DetectionReport.CONTRACT);
        out("");
        out("Clusters/slots: " + r.detection().stats);
        Map<String, Integer> byStmtSource = new TreeMap<>();
        for (StatementContent s : statements) byStmtSource.merge(s.source(), 1, Integer::sum);
        out("Statement-content rows by source: " + byStmtSource);
        out(DetectionReport.statementLinks(r.detection()));
        out("");
        out("DETECTION");
        out(DetectionReport.table(r.detection()));
        out(DetectionReport.dropped(r.detection()));
        out(checks);
        if (fetched != null && !fetched.folderFiles.isEmpty()) out(folderReport(fetched, r.detection(), derived, statements));
        if (fetched != null) writeUnlockList(fetched, r.detection());
        else out("TO-UNLOCK: not written on a cache run (message ids are never cached)");
        out("TIMINGS ms: " + timings);
    }

    private static final class Fetched {
        List<MailDerived> derived;
        List<StatementContent> statements;
        /** Statement-role mails with PDFs, for the unlock list only; never cached or reported. */
        List<UnlockSelector.StatementMail> statementMails = new ArrayList<>();
        String gmailAddress;
        /** Folder documents by report label (a file number or position, never a file name). */
        Map<StatementContent, String> folderDocs = new java.util.IdentityHashMap<>();
        List<String[]> folderFiles = new ArrayList<>();
    }

    /** Writes TO-UNLOCK.html/.txt into the statements folder; only the row counts reach stdout. */
    private void writeUnlockList(Fetched f, com.financeos.gmail.accountdetect.Detection d) throws IOException {
        List<UnlockSelector.Row> rows = UnlockSelector.select(f.statementMails, d);
        Path dir = statementsDir == null || statementsDir.isBlank() ? Path.of(outDir).resolve("statements") : Path.of(statementsDir);
        Files.createDirectories(dir);
        String auth = java.net.URLEncoder.encode(f.gmailAddress == null ? "" : f.gmailAddress, StandardCharsets.UTF_8);
        StringBuilder txt = new StringBuilder("bank | last4 | yyyy-mm | reason | link\n");
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Statements to unlock</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:16px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px 8px;text-align:left}</style>"
                + "</head><body><table><tr><th>bank</th><th>last4</th><th>yyyy-mm</th><th>reason</th><th>link</th></tr>\n");
        Map<String, Integer> byReason = new TreeMap<>();
        for (UnlockSelector.Row row : rows) {
            String link = "https://mail.google.com/mail/u/?authuser=" + auth + "#all/" + row.ref();
            String l4 = row.last4() == null ? "unknown" : row.last4();
            txt.append(row.bank()).append(" | ").append(l4).append(" | ").append(row.month()).append(" | ").append(row.reason()).append(" | ").append(link).append('\n');
            html.append("<tr><td>").append(esc(row.bank())).append("</td><td>").append(esc(l4)).append("</td><td>").append(row.month())
                    .append("</td><td>").append(esc(row.reason())).append("</td><td><a href=\"").append(esc(link)).append("\" target=\"_blank\">open</a></td></tr>\n");
            byReason.merge(row.reason(), 1, Integer::sum);
        }
        html.append("</table></body></html>\n");
        Files.writeString(dir.resolve("TO-UNLOCK.txt"), txt.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("TO-UNLOCK.html"), html.toString(), StandardCharsets.UTF_8);
        out("TO-UNLOCK written: rows=" + rows.size() + " by reason=" + byReason);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private Fetched fetch(Map<String, Long> timings) throws Exception {
        GmailConnection connection = connectionRepository.findAll().stream()
                .filter(c -> c.getId() != null && c.getId().toString().equals(connectionId.trim()))
                .findFirst().orElseThrow(() -> new IllegalStateException("connection not found"));
        String userId = connection.getUser().getId().toString();
        List<String> senderAddresses = senderRepository.findByUserId(connection.getUser().getId()).stream()
                .map(GmailSender::getSenderAddress).filter(x -> x != null && !x.isBlank())
                .map(x -> x.trim().toLowerCase(Locale.ROOT)).toList();
        Gmail svc = engine.createService(connection);
        Set<String> tokens = new LinkedHashSet<>();
        for (String d : BankDirectory.allDomains()) tokens.add(d.toLowerCase(Locale.ROOT));
        tokens.addAll(senderAddresses);

        // ---- 1. inventory: list per sender token, concurrently
        long t0 = System.currentTimeMillis();
        AtomicInteger listCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<String>>> lf = new ArrayList<>();
        for (String t : tokens) lf.add(pool.submit(() -> listAll(svc, "from:" + t, listCalls)));
        Set<String> ids = new LinkedHashSet<>();
        for (Future<List<String>> f : lf) ids.addAll(f.get());
        pool.shutdown();
        timings.put("1 list", System.currentTimeMillis() - t0);
        out("STAGE 1 list: tokens=" + tokens.size() + " list calls=" + listCalls.get() + " ids=" + ids.size());

        // ---- 2. headers for everything (metadata batches, paced under quota)
        t0 = System.currentTimeMillis();
        Map<String, MailFeature> headers = fetchMetadata(svc, new ArrayList<>(ids));
        timings.put("2 metadata", System.currentTimeMillis() - t0);
        out("STAGE 2 metadata: fetched=" + headers.size() + " of " + ids.size() + " (" + batchSize + " per batch, " + getsPerSecond + " gets/s)");

        // ---- 3. roles from headers, choose which bodies to fetch
        t0 = System.currentTimeMillis();
        List<String> order = new ArrayList<>(headers.keySet());
        Map<String, MailDerived> headerDerived = new HashMap<>();
        for (String id : order) headerDerived.put(id, FeatureExtractor.derive(headers.get(id)));
        Map<String, Role> roles = Detector.clusterRoles(new ArrayList<>(headerDerived.values()));
        Map<String, List<String>> byCluster = new HashMap<>();
        for (String id : order) byCluster.computeIfAbsent(headerDerived.get(id).clusterId(), k -> new ArrayList<>()).add(id);
        List<String> wantBody = new ArrayList<>();
        Map<Role, Integer> mailsByRole = new TreeMap<>();
        for (var e : byCluster.entrySet()) {
            Role r = roles.get(e.getKey());
            mailsByRole.merge(r, e.getValue().size(), Integer::sum);
            List<String> ms = new ArrayList<>(e.getValue());
            ms.sort(Comparator.comparing(id -> headers.get(id).date(), Comparator.nullsLast(Comparator.naturalOrder())));
            if (FULL_ROLES.contains(r)) wantBody.addAll(ms);
            else if (r == Role.TXN_ALERT) {
                int n = Math.min(alertSample, ms.size());
                for (int i = 0; i < n; i++) wantBody.add(ms.get((int) ((long) i * ms.size() / n)));
            }
        }
        timings.put("3 cluster+select", System.currentTimeMillis() - t0);
        out("STAGE 3 clusters=" + byCluster.size() + " mails by cluster role=" + mailsByRole + " bodies wanted=" + wantBody.size());

        // ---- 4. full bodies for the chosen mails
        t0 = System.currentTimeMillis();
        Map<String, GmailMessage> full = fetchFull(svc, wantBody);
        timings.put("4 full bodies", System.currentTimeMillis() - t0);
        out("STAGE 4 full: fetched=" + full.size() + " of " + wantBody.size());

        Map<String, MailFeature> features = new LinkedHashMap<>(headers);
        for (var e : full.entrySet()) {
            GmailMessage m = e.getValue();
            MailFeature h = headers.get(e.getKey());
            List<String> names = m.attachments() == null ? List.of() : m.attachments().stream().map(GmailAttachment::filename).toList();
            features.put(e.getKey(), new MailFeature(h.senderDomain(), h.date(), h.subject(), h.snippet(), m.getStrippedText(), names));
        }

        // ---- 5. statement content: unprotected PDFs from statement mails, the local folder, and app-parsed statements
        t0 = System.currentTimeMillis();
        List<StatementContent> statements = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> pdfCounts = new TreeMap<>();
        for (String k : List.of("seen", "OPENED", "PROTECTED", "FAILED", "NO_TEXT", "NO_BANK", "download failed")) pdfCounts.put(k, new AtomicInteger());
        List<String[]> pdfJobs = new ArrayList<>();
        Map<String, Set<String>> pdfOutcomeByMail = new ConcurrentHashMap<>();
        Set<String> mailPdfHashes = ConcurrentHashMap.newKeySet();
        Map<String, Integer> pdfFlags = new TreeMap<>();
        for (var e : full.entrySet()) {
            Role r = roles.get(headerDerived.get(e.getKey()).clusterId());
            if (r != Role.STATEMENT || e.getValue().attachments() == null) continue;
            for (GmailAttachment a : e.getValue().attachments()) {
                String fn = a.filename() == null ? "" : a.filename().toLowerCase(Locale.ROOT);
                if (fn.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(a.mimeType())) pdfJobs.add(new String[]{e.getKey(), a.attachmentId()});
            }
        }
        pdfCounts.get("seen").addAndGet(pdfJobs.size());
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L / getsPerSecond);
        ExecutorService pdfPool = Executors.newFixedThreadPool(6);
        List<Future<?>> pf = new ArrayList<>();
        for (String[] job : pdfJobs) {
            pf.add(pdfPool.submit(() -> {
                MailFeature h = headers.get(job[0]);
                String bank = headerDerived.get(job[0]).bank();
                byte[] bytes = null;
                for (int attempt = 0; attempt < 5 && bytes == null; attempt++) {
                    pace(slot, gap);
                    try {
                        bytes = svc.users().messages().attachments().get("me", job[0], job[1]).execute().decodeData();
                    } catch (GoogleJsonResponseException ex) {
                        if (ex.getStatusCode() == 429 || ex.getStatusCode() >= 500) backoff(slot, attempt);
                        else break;
                    } catch (IOException ex) {
                        backoff(slot, attempt);
                    }
                }
                if (bytes == null) {
                    pdfCounts.get("download failed").incrementAndGet();
                    return;
                }
                mailPdfHashes.add(sha256(bytes));
                YearMonth fallback = h.date() == null ? null : YearMonth.from(h.date().atZone(IST));
                StatementPdfReader.Result res = StatementPdfReader.read(bytes, "PDF_MAIL", bank, fallback);
                if (res.content() != null) {
                    // the PDF is its mail's evidence: tag it with that mail's layout and date (no id)
                    MailDerived origin = headerDerived.get(job[0]);
                    res = new StatementPdfReader.Result(res.status(), res.content().withOrigin(origin.clusterId(), origin.date()), res.flags());
                }
                pdfCounts.get(res.status().name()).incrementAndGet();
                pdfOutcomeByMail.computeIfAbsent(job[0], k -> ConcurrentHashMap.newKeySet()).add(res.status().name());
                synchronized (pdfFlags) {
                    for (String fl : res.flags()) pdfFlags.merge(fl, 1, Integer::sum);
                }
                if (res.content() != null) statements.add(res.content());
            }));
        }
        for (Future<?> f : pf) f.get();
        pdfPool.shutdown();
        timings.put("5a mail PDFs", System.currentTimeMillis() - t0);
        out("STAGE 5a statement PDFs in mail: " + pdfCounts);
        out("  opened mail PDFs, shape flags: " + pdfFlags);

        t0 = System.currentTimeMillis();
        Map<String, Integer> folder = new TreeMap<>(Map.of("files", 0, "not a pdf", 0, "OPENED", 0, "PROTECTED", 0, "FAILED", 0, "NO_TEXT", 0, "NO_BANK", 0));
        Map<String, Integer> folderFlags = new TreeMap<>();
        Map<StatementContent, String> folderDocs = new java.util.IdentityHashMap<>();
        List<String[]> folderFiles = new ArrayList<>();   // label, outcome (null: decided by the detector)
        Path dir = statementsDir == null || statementsDir.isBlank() ? Path.of(outDir).resolve("statements") : Path.of(statementsDir);
        if (Files.isDirectory(dir)) {
            // any file name, any depth: the document's own content decides bank, number and period
            List<Path> files;
            try (var stream = Files.walk(dir)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            }
            int other = 0;
            for (Path p : files) {
                folder.merge("files", 1, Integer::sum);
                String name = p.getFileName().toString();
                // only *.pdf files are ever opened; anything else (scripts, notes, TO-UNLOCK.*) is skipped unread
                if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    folder.merge("not a pdf", 1, Integer::sum);
                    continue;
                }
                // a label for the report: the owner's numbered copies by number, anything else by position (never its
                // name, nor its sub-folder's name)
                String label = name.matches("\\d{1,4}\\.pdf") ? name.substring(0, name.length() - 4)
                        : (dir.relativize(p).getNameCount() > 1 ? "folder#" : "#") + (++other);
                StatementPdfReader.Result res;
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    if (!looksLikePdf(bytes)) {
                        folder.merge("not a pdf", 1, Integer::sum);
                        folderFiles.add(new String[]{label, "not a pdf"});
                        continue;
                    }
                    res = StatementPdfReader.read(bytes, "PDF_FOLDER", null, null);
                    // an encrypted file is PROTECTED and skipped (no password is ever tried); an open one that is
                    // byte-identical to a mail's PDF is that mail's evidence and counts once, there
                    if (res.status() != StatementPdfReader.Status.PROTECTED && mailPdfHashes.contains(sha256(bytes))) {
                        folder.merge("byte-identical to a mail PDF", 1, Integer::sum);
                        folderFiles.add(new String[]{label, "byte-identical to a mail PDF (counted once, as the mail's)"});
                        continue;
                    }
                } catch (IOException e) {
                    res = new StatementPdfReader.Result(StatementPdfReader.Status.FAILED, null);
                }
                folder.merge(res.status().name(), 1, Integer::sum);
                for (String fl : res.flags()) folderFlags.merge(fl, 1, Integer::sum);
                if (res.content() != null) {
                    statements.add(res.content());
                    folderDocs.put(res.content(), label);
                }
                folderFiles.add(new String[]{label, res.content() != null ? null : res.status().name().toLowerCase(Locale.ROOT)});
            }
        }
        timings.put("5b folder PDFs", System.currentTimeMillis() - t0);
        out("STAGE 5b statement PDFs in folder (configured=" + !(statementsDir == null || statementsDir.isBlank()) + ", exists=" + Files.isDirectory(dir)
                + ", recursive): " + folder);
        out("  opened folder PDFs, shape flags: " + folderFlags);

        t0 = System.currentTimeMillis();
        int[] db = new int[3]; // rows, used, unmapped
        jdbc.query("SELECT s.statement_type, s.bank_name, s.account_number_masked, s.period_end, a.bank, d.credit_limit "
                        + "FROM statements s JOIN accounts a ON a.id = s.account_id "
                        + "LEFT JOIN statement_credit_card_details d ON d.statement_id = s.id "
                        + "WHERE s.user_id = ? AND (s.verdict IS NULL OR s.verdict <> 'REJECTED')",
                rs -> {
                    db[0]++;
                    String masked = rs.getString(3);
                    String digits = masked == null ? "" : masked.replaceAll("\\D", "");
                    String bank = dbBank(rs.getString(5), rs.getString(2));
                    java.sql.Date pe = rs.getDate(4);
                    if (digits.length() < 4 || bank == null || pe == null) {
                        db[2]++;
                        return;
                    }
                    String type = rs.getString(1);
                    Kind kind = "credit_card".equals(type) ? Kind.CREDIT_CARD : "bank_account".equals(type) ? Kind.ACCOUNT : null;
                    java.math.BigDecimal limit = rs.getBigDecimal(6);
                    statements.add(new StatementContent("DB", bank, digits.substring(digits.length() - 4), kind,
                            YearMonth.from(pe.toLocalDate()), null, limit == null ? null : StatementContent.bucket(limit.doubleValue()), List.of(), false));
                    db[1]++;
                }, userId);
        timings.put("5c DB statements", System.currentTimeMillis() - t0);
        out("STAGE 5c app-parsed statements in DB: rows=" + db[0] + " used=" + db[1] + " unmapped (no bank/last4/period)=" + db[2]
                + " (no product/add-on columns exist; limit bucket from credit_limit)");

        // ---- 6. derive
        t0 = System.currentTimeMillis();
        List<MailDerived> derived = new ArrayList<>();
        for (String id : order) derived.add(FeatureExtractor.derive(features.get(id)));
        timings.put("6 derive", System.currentTimeMillis() - t0);

        Fetched f = new Fetched();
        f.derived = derived;
        f.statements = new ArrayList<>(statements);
        f.folderDocs = folderDocs;
        f.folderFiles = folderFiles;
        f.gmailAddress = connection.getEmail();
        for (int i = 0; i < order.size(); i++) {
            Set<String> outcomes = pdfOutcomeByMail.get(order.get(i));
            if (outcomes == null) continue;
            MailDerived md = derived.get(i);
            Set<String> l4s = new java.util.TreeSet<>();
            for (MailDerived.Slot sl : md.slots()) if (!sl.label().rejected()) l4s.add(sl.last4());
            f.statementMails.add(new UnlockSelector.StatementMail(order.get(i), md.bank(), md.clusterId(), md.date(), l4s,
                    outcomes.contains("PROTECTED"), outcomes.contains("OPENED")));
        }
        return f;
    }

    private static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Per folder file: outcome, bank, last4, yyyy-mm and the fields it contributed (dictionary variant name, add-on
     * count, limit bucket, closure flag); the per-entity diff against an email-only detection; and statement-vs-email
     * disagreements by code. Never file names or document text.
     */
    private static String folderReport(Fetched f, com.financeos.gmail.accountdetect.Detection d, List<MailDerived> derived,
                                       List<StatementContent> statements) {
        StringBuilder sb = new StringBuilder("FOLDER FILES (label | outcome | bank | last4 | yyyy-mm | contributed)\n");
        Map<StatementContent, com.financeos.gmail.accountdetect.Detection.StatementLink> linkOf = new java.util.IdentityHashMap<>();
        for (var l : d.statementLinks) if (l.doc() != null) linkOf.put(l.doc(), l);
        Map<StatementContent, String> byLabel = f.folderDocs;
        Map<String, StatementContent> docOfLabel = new HashMap<>();
        byLabel.forEach((doc, label) -> docOfLabel.put(label, doc));
        Map<String, Integer> outcomes = new TreeMap<>();
        List<String[]> rows = new ArrayList<>(f.folderFiles);
        rows.sort(Comparator.comparing((String[] x) -> x[0].matches("\\d+") ? "0" : "1").thenComparing(x -> x[0].matches("\\d+") ? String.format("%06d", Integer.parseInt(x[0])) : x[0]));
        com.financeos.gmail.accountdetect.Detection emailOnly = Stability.detectStable(derived,
                statements.stream().filter(s -> !"PDF_FOLDER".equals(s.source())).toList(), 42L).detection();
        Map<String, Integer> disagree = new TreeMap<>();
        for (String[] row : rows) {
            String outcome = row[1];
            StatementContent doc = docOfLabel.get(row[0]);
            String bank = "-", l4 = "-", ym = "-", contributed = "-";
            if (doc != null) {
                var l = linkOf.get(doc);
                outcome = l == null ? "?" : l.outcome();
                bank = doc.bank() == null ? "-" : doc.bank();
                l4 = l != null && l.last4() != null ? l.last4() : doc.last4() == null ? "unknown" : doc.last4();
                ym = doc.period() == null ? "-" : doc.period().toString();
                List<String> c = new ArrayList<>();
                boolean linked = l != null && l.last4() != null && !outcome.equals(Detector.LINK_SAME_AS_MAIL);
                if (linked) {
                    if (doc.variant() != null) c.add("variant=" + doc.variant());
                    if (!doc.addOnLast4().isEmpty()) c.add("add-on x" + doc.addOnLast4().size());
                    if (doc.limitBucket() != null) c.add("limit=" + doc.limitBucket());
                    if (doc.mentionsClosure()) c.add("closure");
                    if (c.isEmpty()) c.add("existence/kind only");
                    // statement vs email
                    var e = emailOnly.find(doc.bank(), l.last4());
                    if (e == null) {
                        boolean other = emailOnly.entities.stream().anyMatch(x -> x.last4.equals(l.last4()) && !x.bank.equals(doc.bank()));
                        String code = other ? "BANK" : "NO_EMAIL_ENTITY";
                        c.add("disagree:" + code);
                        disagree.merge(code, 1, Integer::sum);
                    } else {
                        if (e.kind != null && doc.kind() != null && e.kind != doc.kind()) {
                            c.add("disagree:KIND");
                            disagree.merge("KIND", 1, Integer::sum);
                        }
                        if (e.variant != null && doc.variant() != null && !e.variant.equalsIgnoreCase(doc.variant())) {
                            c.add("disagree:VARIANT");
                            disagree.merge("VARIANT", 1, Integer::sum);
                        }
                    }
                }
                contributed = String.join(", ", c);
                if (!linked) contributed = "-";
            }
            outcomes.merge(outcome, 1, Integer::sum);
            sb.append("  ").append(row[0]).append(" | ").append(outcome).append(" | ").append(bank).append(" | ").append(l4)
                    .append(" | ").append(ym).append(" | ").append(contributed).append('\n');
        }
        sb.append("FOLDER OUTCOMES: ").append(outcomes).append('\n');
        sb.append("STATEMENT vs EMAIL disagreements by code: ").append(disagree).append('\n');
        sb.append("PER-ENTITY DIFF vs email-only (key | exists | kind | variant | lifecycle | limit; email-only -> with folder)\n");
        Set<String> keys = new java.util.TreeSet<>();
        d.entities.forEach(e -> keys.add(e.key()));
        emailOnly.entities.forEach(e -> keys.add(e.key()));
        int changed = 0;
        for (String k : keys) {
            int i = k.indexOf(':');
            var a = emailOnly.find(k.substring(0, i), k.substring(i + 1));
            var b = d.find(k.substring(0, i), k.substring(i + 1));
            String sa = a == null ? "absent" : facts(a), sb2 = b == null ? "absent" : facts(b);
            if (sa.equals(sb2)) continue;
            changed++;
            sb.append("  ").append(k).append(" | ").append(sa).append(" -> ").append(sb2).append('\n');
        }
        sb.append("  entities changed: ").append(changed).append(" of ").append(keys.size()).append('\n');
        return sb.toString();
    }

    private static String facts(com.financeos.gmail.accountdetect.Detection.Entity e) {
        return e.existsConf + " | " + (e.kind == null ? "-" : e.kind + "/" + e.kindConf) + " | "
                + (e.variant == null ? "-" : com.financeos.gmail.accountdetect.ProductCatalog.printable(e.bank, e.variant) + "/" + e.variantConf) + " | "
                + (e.lifecycle == null ? "-" : e.lifecycle + "/" + e.lifecycleConf + (e.linkedTo == null ? "" : "->" + e.linkedTo)) + " | "
                + (e.limitBucket == null ? "-" : e.limitBucket + "/" + e.limitConf);
    }

    private static boolean looksLikePdf(byte[] b) {
        int n = Math.min(b.length, 1024);
        for (int i = 0; i + 4 < n; i++) if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') return true;
        return false;
    }

    private static String dbBank(String accountBank, String bankName) {
        if (accountBank != null) {
            String b = accountBank.trim().toLowerCase(Locale.ROOT);
            for (String d : BankDirectory.allDomains()) {
                String id = BankDirectory.bankForDomain(d);
                if (b.equals(id)) return id;
            }
        }
        if (bankName != null) {
            List<String> found = BankDirectory.issuersIn(bankName.toLowerCase(Locale.ROOT));
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------

    private static List<String> listAll(Gmail svc, String query, AtomicInteger calls) throws IOException {
        List<String> ids = new ArrayList<>();
        String page = null;
        do {
            calls.incrementAndGet();
            ListMessagesResponse r = svc.users().messages().list("me").setQ(query).setMaxResults(500L).setPageToken(page)
                    .setFields("nextPageToken,messages/id").execute();
            if (r == null) break;
            if (r.getMessages() != null) for (Message m : r.getMessages()) ids.add(m.getId());
            page = r.getNextPageToken();
        } while (page != null);
        return ids;
    }

    private static void pace(AtomicLong slot, long gapNanos) {
        long mine = slot.getAndAdd(gapNanos);
        long wait = mine - System.nanoTime();
        if (wait > 0) LockSupport.parkNanos(wait);
    }

    private static void backoff(AtomicLong slot, int attempt) {
        long back = (1000L << attempt) + ThreadLocalRandom.current().nextLong(500);
        slot.accumulateAndGet(System.nanoTime() + back * 1_000_000L, Math::max);
        try {
            Thread.sleep(back);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, MailFeature> fetchMetadata(Gmail svc, List<String> ids) throws Exception {
        Map<String, MailFeature> result = new ConcurrentHashMap<>();
        List<String> pending = ids;
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L * batchSize / getsPerSecond);
        int retries = 0;
        for (int attempt = 0; attempt < 6 && !pending.isEmpty(); attempt++) {
            List<String> retry = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += batchSize) {
                List<String> chunk = pending.subList(i, Math.min(pending.size(), i + batchSize));
                fs.add(pool.submit(() -> {
                    pace(slot, gap);
                    try {
                        BatchRequest batch = svc.batch();
                        for (String id : chunk) {
                            svc.users().messages().get("me", id).setFormat("metadata").setMetadataHeaders(List.of("From", "Subject"))
                                    .setFields("id,snippet,internalDate,payload/headers")
                                    .queue(batch, new JsonBatchCallback<Message>() {
                                        @Override
                                        public void onSuccess(Message m, HttpHeaders h) {
                                            result.put(id, toFeature(m));
                                        }

                                        @Override
                                        public void onFailure(GoogleJsonError e, HttpHeaders h) {
                                            if (e.getCode() == 429 || e.getCode() >= 500 || e.getCode() == 403) retry.add(id);
                                        }
                                    });
                        }
                        batch.execute();
                    } catch (IOException e) {
                        retry.addAll(chunk);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdown();
            if (retry.isEmpty()) break;
            retries += retry.size();
            backoff(slot, attempt);
            pending = new ArrayList<>(retry);
        }
        out("  metadata retried ids=" + retries);
        Map<String, MailFeature> ordered = new LinkedHashMap<>();
        for (String id : ids) if (result.containsKey(id)) ordered.put(id, result.get(id));
        return ordered;
    }

    private static final Pattern DOMAIN = Pattern.compile("@([A-Za-z0-9.-]+)");

    private static MailFeature toFeature(Message m) {
        String from = "", subject = "";
        if (m.getPayload() != null && m.getPayload().getHeaders() != null) {
            for (MessagePartHeader h : m.getPayload().getHeaders()) {
                if ("From".equalsIgnoreCase(h.getName())) from = h.getValue();
                else if ("Subject".equalsIgnoreCase(h.getName())) subject = h.getValue();
            }
        }
        String d = "";
        Matcher mm = DOMAIN.matcher(from == null ? "" : from);
        while (mm.find()) d = mm.group(1);
        Instant date = m.getInternalDate() == null ? null : Instant.ofEpochMilli(m.getInternalDate());
        return new MailFeature(d.toLowerCase(Locale.ROOT), date, subject == null ? "" : subject, m.getSnippet() == null ? "" : m.getSnippet(), null, List.of());
    }

    private Map<String, GmailMessage> fetchFull(Gmail svc, List<String> ids) throws Exception {
        Map<String, GmailMessage> out = new ConcurrentHashMap<>();
        AtomicLong slot = new AtomicLong(System.nanoTime());
        long gap = (long) (1_000_000_000L / getsPerSecond);
        AtomicInteger retries = new AtomicInteger(), failed = new AtomicInteger(), gone = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> fs = new ArrayList<>();
        for (String id : ids) {
            fs.add(pool.submit(() -> {
                for (int attempt = 0; ; attempt++) {
                    pace(slot, gap);
                    try {
                        out.put(id, engine.fetchMessageDetails(svc, id));
                        return;
                    } catch (MessageGoneException e) {
                        gone.incrementAndGet();
                        return;
                    } catch (GmailEngineException e) {
                        if (e.getErrorType() == GmailError.RATE_LIMIT && attempt < 5) {
                            retries.incrementAndGet();
                            backoff(slot, attempt);
                            continue;
                        }
                        failed.incrementAndGet();
                        return;
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        return;
                    }
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        this.out("  full retries=" + retries.get() + " failed=" + failed.get() + " gone=" + gone.get());
        return out;
    }

}
