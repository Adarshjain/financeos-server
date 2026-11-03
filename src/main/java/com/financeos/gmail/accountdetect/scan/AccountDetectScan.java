package com.financeos.gmail.accountdetect.scan;

import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.FeatureExtractor;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.MailFeature;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.Role;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The account detector's mail pipeline, shared by the v3 dev runner and the ACCOUNT_DETECT job: list bank-sender mail
 * by sender token, read headers, cluster layouts and vote roles, read full bodies for the roles that need them (every
 * statement / due reminder / card or account service mail, an evenly spaced sample of each transaction-alert layout),
 * read open statement PDFs (a protected one is recorded, never opened) and derive {@link MailDerived} per mail.
 *
 * <p>Incremental use: {@code knownIds} are skipped, and {@code prior} (already stored mails) joins the role vote and
 * counts toward each alert layout's body sample. With a {@code sink}, mails are handed over in chunks as they are done
 * (header-only mails first), so an interrupted scan keeps what it finished. Report lines carry counts only.
 */
public final class AccountDetectScan {

    public static final Set<Role> FULL_ROLES = Set.of(Role.STATEMENT, Role.DUE_REMINDER, Role.CARD_SERVICE, Role.ACCOUNT_SERVICE);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * An already stored mail: its features, whether its body was read, and its header-only role vote. The cluster-role
     * vote counts header votes only, so a cluster votes the same in a first scan and in any later run.
     */
    public record Prior(MailDerived derived, boolean bodyFetched, Role headerVote) {
        public Prior(MailDerived derived, boolean bodyFetched) {
            this(derived, bodyFetched, null);
        }
    }

    public interface ChunkSink {
        void accept(List<ScannedMail> chunk) throws Exception;
    }

    /** Called after each handed-over chunk; may throw to stop the scan (e.g. a cancelled job). */
    public interface Progress {
        void update(int seen, int total) throws Exception;
    }

    /**
     * @param mails      every newly scanned mail, in listing order
     * @param statements statement content read from their PDFs (completion order)
     * @param pdfHashes  SHA-256 of every downloaded mail PDF (in memory only)
     * @param headersMissing listed ids whose headers could not be read (after the source's retries); not in {@code mails}
     */
    public record Result(List<ScannedMail> mails, List<StatementContent> statements, Set<String> pdfHashes, int listed, int skippedKnown,
                         List<String> headersMissing) {
        /** The newest internal date among the scanned mails, or null. */
        public Instant newest() {
            return mails.stream().map(ScannedMail::internalDate).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }

        /** Every id a read failed for: headers missing, then scanned mails with a failed body or PDF read. */
        public List<String> incompleteIds() {
            List<String> out = new ArrayList<>(headersMissing);
            for (ScannedMail m : mails) if (m.incomplete()) out.add(m.messageId());
            return out;
        }
    }

    private final int alertSample;
    private final int chunkSize;

    /**
     * @param chunkSize mails whose bodies are read per chunk; {@link Integer#MAX_VALUE} reads them all in one go
     */
    public AccountDetectScan(int alertSample, int chunkSize) {
        this.alertSample = alertSample;
        this.chunkSize = Math.max(1, chunkSize);
    }

    public Result run(MailSource src, Collection<String> tokens, Instant after, Set<String> knownIds, List<Prior> prior, String salt,
                      ChunkSink sink, Progress progress, Consumer<String> out, Map<String, Long> timings) throws Exception {
        Set<String> known = knownIds == null ? Set.of() : knownIds;
        List<Prior> priors = prior == null ? List.of() : prior;

        // ---- 1. inventory: list per sender token
        long t0 = System.currentTimeMillis();
        MailSource.Listing listing = src.list(tokens, after);
        List<String> ids = new ArrayList<>();
        for (String id : listing.ids()) if (!known.contains(id)) ids.add(id);
        int skipped = listing.ids().size() - ids.size();
        timings.put("1 list", System.currentTimeMillis() - t0);
        out.accept("STAGE 1 list: tokens=" + tokens.size() + " list calls=" + listing.calls() + " ids=" + listing.ids().size()
                + (known.isEmpty() ? "" : " already stored=" + skipped));

        // ---- 2. headers for everything new (metadata batches, paced under quota)
        t0 = System.currentTimeMillis();
        Map<String, MailFeature> headers = src.metadata(ids, out);
        timings.put("2 metadata", System.currentTimeMillis() - t0);
        out.accept("STAGE 2 metadata: fetched=" + headers.size() + " of " + ids.size() + " (" + metaNote(src) + ")");

        // ---- 3. roles from headers (with the stored mails' votes), choose which bodies to fetch
        t0 = System.currentTimeMillis();
        List<String> order = new ArrayList<>(headers.keySet());
        Map<String, MailDerived> headerDerived = new HashMap<>();
        for (String id : order) headerDerived.put(id, FeatureExtractor.derive(headers.get(id), salt));
        List<MailDerived> voters = new ArrayList<>();
        Map<String, Integer> priorBodies = new HashMap<>();
        for (Prior p : priors) {
            voters.add(withVote(p.derived(), p.headerVote()));
            if (p.bodyFetched()) priorBodies.merge(p.derived().clusterId(), 1, Integer::sum);
        }
        voters.addAll(headerDerived.values());
        Map<String, Role> roles = Detector.clusterRoles(voters);
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
                // an evenly spaced sample per alert layout; stored bodies of the layout count toward it
                int budget = alertSample - priorBodies.getOrDefault(e.getKey(), 0);
                int n = Math.max(0, Math.min(budget, ms.size()));
                for (int i = 0; i < n; i++) wantBody.add(ms.get((int) ((long) i * ms.size() / n)));
            }
        }
        timings.put("3 cluster+select", System.currentTimeMillis() - t0);
        out.accept("STAGE 3 clusters=" + byCluster.size() + " mails by cluster role=" + mailsByRole + " bodies wanted=" + wantBody.size());

        Map<String, ScannedMail> done = new HashMap<>();
        int total = order.size();
        long deriveMs = 0;
        Set<String> wanted = new HashSet<>(wantBody);
        if (sink != null) {
            // header-only mails are final now: hand them over first
            List<String> headerOnly = order.stream().filter(id -> !wanted.contains(id)).toList();
            for (int i = 0; i < headerOnly.size(); i += 500) {
                long d0 = System.currentTimeMillis();
                List<ScannedMail> chunk = new ArrayList<>();
                for (String id : headerOnly.subList(i, Math.min(headerOnly.size(), i + 500))) {
                    ScannedMail sm = scanned(id, headers.get(id), headerDerived.get(id), roles, false, List.of(), List.of(), salt, null, false);
                    done.put(id, sm);
                    chunk.add(sm);
                }
                deriveMs += System.currentTimeMillis() - d0;
                sink.accept(chunk);
                if (progress != null) progress.update(done.size(), total);
            }
        }

        // ---- 4 + 5a. full bodies, then open statement PDFs from statement mails, chunk by chunk
        List<StatementContent> statements = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> pdfCounts = new TreeMap<>();
        for (String k : List.of("seen", "OPENED", "PROTECTED", "FAILED", "NO_TEXT", "NO_BANK", "download failed")) pdfCounts.put(k, new AtomicInteger());
        Map<String, Integer> pdfFlags = new TreeMap<>();
        Set<String> mailPdfHashes = ConcurrentHashMap.newKeySet();
        int fullFetched = 0;
        if (wantBody.isEmpty()) src.full(List.of(), out);   // keeps the report's retry line
        for (int c = 0; c < wantBody.size(); c += chunkSize) {
            List<String> chunkIds = wantBody.subList(c, Math.min(wantBody.size(), c + chunkSize));
            t0 = System.currentTimeMillis();
            Map<String, GmailMessage> full = src.full(chunkIds, out);
            timings.merge("4 full bodies", System.currentTimeMillis() - t0, Long::sum);
            fullFetched += full.size();

            t0 = System.currentTimeMillis();
            Map<String, List<ScannedMail.Pdf>> pdfsByMail = new HashMap<>();
            Map<String, List<StatementContent>> contentByMail = new HashMap<>();
            List<String[]> pdfJobs = new ArrayList<>();
            for (String id : chunkIds) {
                GmailMessage m = full.get(id);
                if (m == null) continue;
                Role r = roles.get(headerDerived.get(id).clusterId());
                if (r != Role.STATEMENT || m.attachments() == null) continue;
                for (GmailAttachment a : m.attachments()) {
                    String fn = a.filename() == null ? "" : a.filename().toLowerCase(Locale.ROOT);
                    if (fn.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(a.mimeType())) pdfJobs.add(new String[]{id, a.attachmentId()});
                }
            }
            pdfCounts.get("seen").addAndGet(pdfJobs.size());
            MailSource.Attachments fetcher = src.attachments();
            ExecutorService pdfPool = Executors.newFixedThreadPool(6);
            try {
                List<Future<?>> pf = new ArrayList<>();
                ScannedMail.Pdf[] pdfOut = new ScannedMail.Pdf[pdfJobs.size()];
                StatementContent[] contentOut = new StatementContent[pdfJobs.size()];
                for (int j = 0; j < pdfJobs.size(); j++) {
                    String[] job = pdfJobs.get(j);
                    int slot = j;
                    pf.add(pdfPool.submit(() -> {
                        MailFeature h = headers.get(job[0]);
                        MailDerived origin = headerDerived.get(job[0]);
                        byte[] bytes = fetcher.get(job[0], job[1]);
                        if (bytes == null) {
                            pdfCounts.get("download failed").incrementAndGet();
                            pdfOut[slot] = new ScannedMail.Pdf(job[1], ScannedMail.DOWNLOAD_FAILED);
                            return null;
                        }
                        mailPdfHashes.add(sha256(bytes));
                        YearMonth fallback = h.date() == null ? null : YearMonth.from(h.date().atZone(IST));
                        StatementPdfReader.Result res = StatementPdfReader.read(bytes, "PDF_MAIL", origin.bank(), fallback);
                        if (res.content() != null) {
                            // the PDF is its mail's evidence: tag it with that mail's layout and date (no id)
                            res = new StatementPdfReader.Result(res.status(), res.content().withOrigin(origin.clusterId(), origin.date()), res.flags());
                        }
                        pdfCounts.get(res.status().name()).incrementAndGet();
                        pdfOut[slot] = new ScannedMail.Pdf(job[1], res.status().name());
                        synchronized (pdfFlags) {
                            for (String fl : res.flags()) pdfFlags.merge(fl, 1, Integer::sum);
                        }
                        if (res.content() != null) {
                            statements.add(res.content());
                            contentOut[slot] = res.content();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : pf) GmailMailSource.await(f);
                // per mail in attachment order, whatever order the downloads finished in
                for (int j = 0; j < pdfJobs.size(); j++) {
                    String id = pdfJobs.get(j)[0];
                    if (pdfOut[j] != null) pdfsByMail.computeIfAbsent(id, k -> new ArrayList<>()).add(pdfOut[j]);
                    if (contentOut[j] != null) contentByMail.computeIfAbsent(id, k -> new ArrayList<>()).add(contentOut[j]);
                }
            } finally {
                pdfPool.shutdownNow();
            }
            timings.merge("5a mail PDFs", System.currentTimeMillis() - t0, Long::sum);

            long d0 = System.currentTimeMillis();
            List<ScannedMail> chunk = new ArrayList<>();
            for (String id : chunkIds) {
                GmailMessage m = full.get(id);
                List<ScannedMail.Pdf> pdfs = pdfsByMail.getOrDefault(id, List.of());
                // a wanted body or a statement PDF that could not be read leaves the mail incomplete
                boolean incomplete = m == null || pdfs.stream().anyMatch(p -> ScannedMail.DOWNLOAD_FAILED.equals(p.status()));
                ScannedMail sm = scanned(id, headers.get(id), headerDerived.get(id), roles, m != null,
                        pdfs, contentByMail.getOrDefault(id, List.of()), salt, m, incomplete);
                done.put(id, sm);
                chunk.add(sm);
            }
            deriveMs += System.currentTimeMillis() - d0;
            if (sink != null) {
                sink.accept(chunk);
                if (progress != null) progress.update(done.size(), total);
            }
        }
        out.accept("STAGE 4 full: fetched=" + fullFetched + " of " + wantBody.size());
        out.accept("STAGE 5a statement PDFs in mail: " + pdfCounts);
        out.accept("  opened mail PDFs, shape flags: " + pdfFlags);

        // ---- 6. derive what is left (header-only mails when nothing was handed over), in listing order
        long d0 = System.currentTimeMillis();
        List<ScannedMail> mails = new ArrayList<>();
        for (String id : order) {
            ScannedMail sm = done.get(id);
            if (sm == null) sm = scanned(id, headers.get(id), headerDerived.get(id), roles, false, List.of(), List.of(), salt, null, false);
            mails.add(sm);
        }
        timings.put("6 derive", deriveMs + System.currentTimeMillis() - d0);
        if (sink == null && progress != null) progress.update(mails.size(), total);
        List<String> headersMissing = ids.stream().filter(id -> !headers.containsKey(id)).toList();
        return new Result(mails, new ArrayList<>(statements), mailPdfHashes, listing.ids().size(), skipped, headersMissing);
    }

    /** The mail's features with another role vote (the stored header vote of a mail whose body was read). */
    static MailDerived withVote(MailDerived d, Role vote) {
        if (vote == null || vote == d.roleVote()) return d;
        return new MailDerived(d.bank(), d.clusterId(), vote, d.date(), d.clusterKind(), d.slots(), d.variants(), d.events(),
                d.appHits(), d.layout(), d.limits());
    }

    private static ScannedMail scanned(String id, MailFeature h, MailDerived headerOnly, Map<String, Role> roles, boolean bodyFetched,
                                       List<ScannedMail.Pdf> pdfs, List<StatementContent> contents, String salt, GmailMessage full,
                                       boolean incomplete) {
        MailDerived d = headerOnly;
        if (full != null) {
            List<String> names = full.attachments() == null ? List.of() : full.attachments().stream().map(GmailAttachment::filename).toList();
            d = FeatureExtractor.derive(new MailFeature(h.senderDomain(), h.date(), h.subject(), h.snippet(), full.getStrippedText(), names), salt);
        }
        return new ScannedMail(id, h.date(), h.senderDomain(), roles.get(headerOnly.clusterId()), d, bodyFetched,
                new ArrayList<>(contents), new ArrayList<>(pdfs), headerOnly.roleVote(), incomplete);
    }

    private static String metaNote(MailSource src) {
        return src instanceof Describable d ? d.describe() : "";
    }

    /** A source that can describe its pacing for the STAGE 2 line. */
    public interface Describable {
        String describe();
    }

    static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
