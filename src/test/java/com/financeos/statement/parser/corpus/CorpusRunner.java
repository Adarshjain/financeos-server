package com.financeos.statement.parser.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.statement.parser.ParseInfo;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.StatementMeta;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.layout.LayoutRouting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Measures the statement parser over a private corpus kept outside git. Off unless -Dcorpus.dir is given. It writes one
 * snapshot JSON per file and a results.tsv; it never asserts anything and never prints file contents.
 * See README.md in this package.
 */
@EnabledIfSystemProperty(named = "corpus.dir", matches = ".+")
class CorpusRunner {

    static final List<String> RESULT_COLUMNS = List.of("sha256", "kind", "issuer", "bytes", "wall_ms", "failure_type",
            "statement_type", "verdict", "mode", "chain_pct", "checksum_ok", "txn_count", "rows_dropped", "null_fields", "layout_id");

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** One corpus file to parse: the readable bytes' sha256, its extension and the manifest labels. */
    record Entry(String sha256, String ext, String kind, String issuer) {
    }

    /** The forced reader is process-wide: never leave it set for the next test. */
    @AfterEach
    void resetForcedReader() {
        LayoutRouting.setForcedReader(null);
    }

    @Test
    void run() throws Exception {
        Path dir = Path.of(System.getProperty("corpus.dir"));
        String outProp = System.getProperty("corpus.out");
        if (outProp == null || outProp.isBlank()) throw new IllegalStateException("corpus.out is required");
        Path out = Path.of(outProp);
        boolean all = "all".equals(System.getProperty("corpus.scope", "in_scope"));
        long timeoutSec = Long.parseLong(System.getProperty("corpus.timeoutSec", "60"));
        // -Dcorpus.reader=v1 ignores every layout profile; =layout reads with the matching profile as if ACTIVE
        LayoutRouting.setForcedReader(System.getProperty("corpus.reader"));

        List<Entry> entries = readManifest(Files.readAllLines(dir.resolve("manifest.tsv"), StandardCharsets.UTF_8), all);
        Files.createDirectories(out);
        List<String> results = new ArrayList<>();
        results.add(String.join("\t", RESULT_COLUMNS));
        int ok = 0;
        int failures = 0;
        Map<String, Integer> verdicts = new TreeMap<>();
        for (Entry e : entries) {
            Path file = dir.resolve("files").resolve(e.sha256() + "." + e.ext());
            byte[] bytes = Files.readAllBytes(file);
            long t0 = System.nanoTime();
            ParsedStatement ps = null;
            Throwable failure = null;
            try {
                ps = parseWithTimeout(bytes, timeoutSec);
            } catch (Throwable t) {
                failure = t;
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            ObjectNode snap = failure == null ? CorpusSnapshot.of(ps) : CorpusSnapshot.ofFailure(failure);
            Files.writeString(out.resolve(e.sha256() + ".json"), PRETTY.writeValueAsString(snap) + "\n", StandardCharsets.UTF_8);
            results.add(resultRow(e, bytes.length, wallMs, ps, failure));
            if (failure == null) {
                ok++;
                verdicts.merge(String.valueOf(ps.parseInfo().verdict), 1, Integer::sum);
            } else {
                failures++;
            }
            Files.write(out.resolve("results.tsv"), results, StandardCharsets.UTF_8);
        }
        Files.write(out.resolve("results.tsv"), results, StandardCharsets.UTF_8);
        System.out.println("CORPUS files=" + entries.size() + " ok=" + ok + " failures=" + failures + " verdicts=" + verdicts);
    }

    /** Parses on a daemon worker thread; a run past the timeout is a failure of type Timeout and the run goes on. */
    static ParsedStatement parseWithTimeout(byte[] bytes, long timeoutSec) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "corpus-parse");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<ParsedStatement> f = ex.submit(() -> StatementParseEngine.parse(bytes, null, null));
            try {
                return f.get(timeoutSec, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                throw new Timeout(">" + timeoutSec + "s");
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof Exception x) throw x;
                if (c instanceof Error x) throw x;
                throw ee;
            }
        } finally {
            ex.shutdownNow();
        }
    }

    /** Named so that CorpusSnapshot.ofFailure reports its simple class name, Timeout. */
    static final class Timeout extends RuntimeException {
        Timeout(String message) {
            super(message);
        }
    }

    /** Distinct non-empty readable_sha256 values in sha order; only in_scope=Y rows unless all is true. */
    static List<Entry> readManifest(List<String> lines, boolean all) {
        if (lines.isEmpty()) return List.of();
        List<String> header = Arrays.asList(lines.get(0).split("\t", -1));
        int iExt = col(header, "ext");
        int iRead = col(header, "readable_sha256");
        int iKind = col(header, "kind");
        int iIssuer = col(header, "issuer");
        int iScope = col(header, "in_scope");
        col(header, "sha256");
        col(header, "locked");
        Map<String, Entry> byHash = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] c = lines.get(i).split("\t", -1);
            String read = cell(c, iRead);
            if (read.isEmpty()) continue;
            if (!all && !"Y".equals(cell(c, iScope))) continue;
            byHash.putIfAbsent(read, new Entry(read, cell(c, iExt), cell(c, iKind), cell(c, iIssuer)));
        }
        return new ArrayList<>(byHash.values());
    }

    private static int col(List<String> header, String name) {
        int i = header.indexOf(name);
        if (i < 0) throw new IllegalStateException("manifest.tsv has no column " + name);
        return i;
    }

    private static String cell(String[] c, int i) {
        return i < c.length ? c[i].trim() : "";
    }

    static String resultRow(Entry e, int bytes, long wallMs, ParsedStatement ps, Throwable failure) {
        List<String> row = new ArrayList<>();
        row.add(e.sha256());
        row.add(e.kind());
        row.add(e.issuer());
        row.add(String.valueOf(bytes));
        row.add(String.valueOf(wallMs));
        if (failure != null) {
            row.add(failure.getClass().getSimpleName());
            for (int i = 0; i < RESULT_COLUMNS.size() - 6; i++) row.add("");
        } else {
            ParseInfo pi = ps.parseInfo();
            row.add("");
            row.add(clean(ps.statementType()));
            row.add(clean(pi.verdict));
            row.add(clean(pi.mode));
            row.add(CorpusSnapshot.money(pi.chainValidationPct));
            row.add(String.valueOf(pi.checksumOk));
            row.add(String.valueOf(ps.transactions().size()));
            row.add(String.valueOf(pi.rowsDropped));
            row.add(String.join(",", nullFields(ps)));
            row.add(clean(ps.layoutId()));
        }
        return String.join("\t", row);
    }

    static List<String> nullFields(ParsedStatement ps) {
        StatementMeta m = ps.meta();
        List<String> nf = new ArrayList<>();
        if (m.accountNumber == null) nf.add("account_number");
        if (m.periodStart == null) nf.add("period_start");
        if (m.periodEnd == null) nf.add("period_end");
        if (m.openingBalance == null) nf.add("opening");
        if (m.closingBalance == null) nf.add("closing");
        if ("credit_card".equals(ps.statementType())) {
            for (String k : new String[]{"payment_due_date", "total_amount_due", "minimum_amount_due", "credit_limit", "previous_balance"}) {
                if (ps.summaryFields().get(k) == null) nf.add(k);
            }
        }
        return nf;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ");
    }
}
