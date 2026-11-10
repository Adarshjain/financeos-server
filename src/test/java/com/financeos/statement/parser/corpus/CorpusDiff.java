package com.financeos.statement.parser.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Compares two CorpusRunner outputs file by file and writes a Markdown report. Off unless -Dcorpus.diff.base is
 * given. The report holds statement values and stays on this machine. See README.md in this package.
 */
@EnabledIfSystemProperty(named = "corpus.diff.base", matches = ".+")
class CorpusDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_EXAMPLES = 5;

    /** What changed in one file; rows are summarised as counts plus up to MAX_EXAMPLES examples each. */
    record FileChange(String name, String verdict, String statementType, String txnCount, List<String> fieldChanges,
                      int rowsAdded, int rowsRemoved, int rowsChanged,
                      List<String> addedExamples, List<String> removedExamples, List<String> changedExamples) {
    }

    /** The whole comparison: names are JSON file names. */
    record Result(int identical, List<FileChange> changed, List<String> onlyInBase, List<String> onlyInHead) {
    }

    @Test
    void diff() throws Exception {
        Path base = Path.of(System.getProperty("corpus.diff.base"));
        String headProp = System.getProperty("corpus.diff.head");
        if (headProp == null || headProp.isBlank()) throw new IllegalStateException("corpus.diff.head is required");
        Path head = Path.of(headProp);
        String reportProp = System.getProperty("corpus.diff.report");
        Path report = reportProp == null || reportProp.isBlank() ? head.resolve("diff.md") : Path.of(reportProp);

        String family = System.getProperty("corpus.diff.family");
        Set<String> only = family == null || family.isBlank() ? null : shasWithLayout(head.resolve("results.tsv"), family);
        requireFamilyFiles(family, only);
        Result result = compareDirs(base, head, only, parseIgnore(System.getProperty("corpus.diff.ignore")));
        Map<String, String> issuers = new HashMap<>(readIssuers(base.resolve("results.tsv")));
        issuers.putAll(readIssuers(head.resolve("results.tsv")));
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, render(result, issuers), StandardCharsets.UTF_8);
        System.out.println("CORPUS-DIFF identical=" + result.identical() + " changed=" + result.changed().size()
                + " onlyInBase=" + result.onlyInBase().size() + " onlyInHead=" + result.onlyInHead().size() + " report=" + report);
        assertNoChangeIfRequired(result, Boolean.getBoolean("corpus.diff.failOnChange"));
    }

    /** Throws an AssertionError listing the changed sha256s when failOnChange is set and anything changed. */
    static void assertNoChangeIfRequired(Result result, boolean failOnChange) {
        if (!failOnChange) return;
        if (result.changed().isEmpty() && result.onlyInBase().isEmpty() && result.onlyInHead().isEmpty()) return;
        List<String> shas = new ArrayList<>();
        result.changed().forEach(c -> shas.add(sha(c.name())));
        result.onlyInBase().forEach(n -> shas.add(sha(n)));
        result.onlyInHead().forEach(n -> shas.add(sha(n)));
        throw new AssertionError("corpus changed: " + String.join(", ", shas));
    }

    static String sha(String jsonName) {
        return jsonName.endsWith(".json") ? jsonName.substring(0, jsonName.length() - 5) : jsonName;
    }

    static Result compareDirs(Path base, Path head) throws IOException {
        return compareDirs(base, head, null, Set.of());
    }

    /** A family filter that matches no file (or a head run without layout_id) is an error, never "no changes". */
    static void requireFamilyFiles(String family, Set<String> shas) {
        if (shas != null && shas.isEmpty()) {
            throw new IllegalStateException("corpus.diff.family=" + family
                    + " matches no file: the head run has no layout_id column or no file read with that layout "
                    + "(run the head with -Dcorpus.reader=layout)");
        }
    }

    /** Field names to leave out of the comparison (at any depth), from a comma-separated property value. */
    static Set<String> parseIgnore(String text) {
        Set<String> out = new TreeSet<>();
        if (text != null) for (String t : text.split(",")) if (!t.isBlank()) out.add(t.strip());
        return out;
    }

    /** The sha256s whose results.tsv row names this layout_id (empty when the column or file is missing). */
    static Set<String> shasWithLayout(Path results, String layoutId) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.exists(results)) return out;
        List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return out;
        List<String> header = List.of(lines.get(0).split("\t", -1));
        int iSha = header.indexOf("sha256");
        int iLayout = header.indexOf("layout_id");
        if (iSha < 0 || iLayout < 0) return out;
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t", -1);
            if (c.length > Math.max(iSha, iLayout) && c[iLayout].equals(layoutId)) out.add(c[iSha]);
        }
        return out;
    }

    private static JsonNode strip(JsonNode n, Set<String> ignore) {
        if (ignore.isEmpty()) return n;
        JsonNode copy = n.deepCopy();
        stripInPlace(copy, ignore);
        return copy;
    }

    private static void stripInPlace(JsonNode n, Set<String> ignore) {
        if (n.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove(ignore);
            n.elements().forEachRemaining(c -> stripInPlace(c, ignore));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(c -> stripInPlace(c, ignore));
        }
    }

    /** As compareDirs, limited to the given sha256s (null = every file) and ignoring the given field names. */
    static Result compareDirs(Path base, Path head, Set<String> onlyShas, Set<String> ignore) throws IOException {
        TreeSet<String> baseNames = jsonNames(base);
        TreeSet<String> headNames = jsonNames(head);
        if (onlyShas != null) {
            baseNames.removeIf(n -> !onlyShas.contains(sha(n)));
            headNames.removeIf(n -> !onlyShas.contains(sha(n)));
        }
        int identical = 0;
        List<FileChange> changed = new ArrayList<>();
        List<String> onlyBase = new ArrayList<>();
        List<String> onlyHead = new ArrayList<>();
        for (String n : baseNames) {
            if (!headNames.contains(n)) {
                onlyBase.add(n);
                continue;
            }
            JsonNode b = strip(MAPPER.readTree(base.resolve(n).toFile()), ignore);
            JsonNode h = strip(MAPPER.readTree(head.resolve(n).toFile()), ignore);
            FileChange c = compare(n, b, h);
            if (c == null) identical++;
            else changed.add(c);
        }
        for (String n : headNames) if (!baseNames.contains(n)) onlyHead.add(n);
        return new Result(identical, changed, onlyBase, onlyHead);
    }

    private static TreeSet<String> jsonNames(Path dir) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(names::add);
        }
        return names;
    }

    /** Null when the two snapshots are identical. */
    static FileChange compare(String name, JsonNode base, JsonNode head) {
        if (base.equals(head)) return null;
        String verdict = change(text(base.at("/parseInfo/verdict")), text(head.at("/parseInfo/verdict")));
        String type = change(text(base.get("statementType")), text(head.get("statementType")));
        JsonNode bt = base.path("transactions");
        JsonNode ht = head.path("transactions");
        String count = bt.size() == ht.size() ? null : bt.size() + " → " + ht.size();

        Map<String, String> bf = new TreeMap<>();
        Map<String, String> hf = new TreeMap<>();
        flatten("", base, bf);
        flatten("", head, hf);
        List<String> fields = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(bf.keySet());
        keys.addAll(hf.keySet());
        for (String k : keys) {
            String o = bf.get(k);
            String n = hf.get(k);
            if (k.equals("statementType") || k.equals("parseInfo.verdict")) continue;
            if (o == null ? n != null : !o.equals(n)) fields.add(k + ": " + show(o) + " → " + show(n));
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changedRows = new ArrayList<>();
        int nAdded = 0;
        int nRemoved = 0;
        int nChanged = 0;
        if (bt.size() == ht.size()) {
            for (int i = 0; i < bt.size(); i++) {
                if (!bt.get(i).equals(ht.get(i))) {
                    nChanged++;
                    if (changedRows.size() < MAX_EXAMPLES) changedRows.add("#" + i + ": " + rowText(bt.get(i)) + " → " + rowText(ht.get(i)));
                }
            }
        } else {
            Map<String, List<JsonNode>> bm = multiset(bt);
            Map<String, List<JsonNode>> hm = multiset(ht);
            TreeSet<String> all = new TreeSet<>(bm.keySet());
            all.addAll(hm.keySet());
            for (String k : all) {
                List<JsonNode> bl = bm.getOrDefault(k, List.of());
                List<JsonNode> hl = hm.getOrDefault(k, List.of());
                for (int i = hl.size(); i < bl.size(); i++) {
                    nRemoved++;
                    if (removed.size() < MAX_EXAMPLES) removed.add(rowText(bl.get(i)));
                }
                for (int i = bl.size(); i < hl.size(); i++) {
                    nAdded++;
                    if (added.size() < MAX_EXAMPLES) added.add(rowText(hl.get(i)));
                }
            }
        }
        return new FileChange(name, verdict, type, count, fields, nAdded, nRemoved, nChanged, added, removed, changedRows);
    }

    private static Map<String, List<JsonNode>> multiset(JsonNode rows) {
        Map<String, List<JsonNode>> m = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            m.computeIfAbsent(text(r.get("date")) + "|" + text(r.get("amount")), k -> new ArrayList<>()).add(r);
        }
        return m;
    }

    private static String rowText(JsonNode r) {
        return text(r.get("date")) + " " + text(r.get("amount")) + " bal " + text(r.get("balance")) + " \"" + text(r.get("description")) + "\"";
    }

    private static void flatten(String path, JsonNode n, Map<String, String> out) {
        if (path.equals("transactions")) return;
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> flatten(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), out));
        } else {
            out.put(path, text(n));
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String show(String v) {
        return v == null ? "null" : v;
    }

    private static String change(String o, String n) {
        return o == null ? (n == null ? null : "null → " + n) : o.equals(n) ? null : o + " → " + show(n);
    }

    static Map<String, String> readIssuers(Path results) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(results)) return m;
        List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return m;
        List<String> header = List.of(lines.get(0).split("\t", -1));
        int iSha = header.indexOf("sha256");
        int iIssuer = header.indexOf("issuer");
        if (iSha < 0 || iIssuer < 0) return m;
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t", -1);
            if (c.length > Math.max(iSha, iIssuer)) m.put(c[iSha], c[iIssuer]);
        }
        return m;
    }

    static String render(Result r, Map<String, String> issuerBySha) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Corpus diff\n\n");
        sb.append("- identical: ").append(r.identical()).append('\n');
        sb.append("- changed: ").append(r.changed().size()).append('\n');
        sb.append("- only in base: ").append(r.onlyInBase().size()).append('\n');
        sb.append("- only in head: ").append(r.onlyInHead().size()).append("\n\n");
        Map<String, List<FileChange>> byIssuer = new TreeMap<>();
        for (FileChange c : r.changed()) {
            String issuer = issuerBySha.getOrDefault(sha(c.name()), "");
            byIssuer.computeIfAbsent(issuer.isEmpty() ? "(unknown issuer)" : issuer, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<FileChange>> e : byIssuer.entrySet()) {
            sb.append("## ").append(e.getKey()).append(" (").append(e.getValue().size()).append(")\n\n");
            for (FileChange c : e.getValue()) block(sb, c);
        }
        section(sb, "Only in base", r.onlyInBase());
        section(sb, "Only in head", r.onlyInHead());
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<String> names) {
        if (names.isEmpty()) return;
        sb.append("## ").append(title).append("\n\n");
        for (String n : names) sb.append("- ").append(sha(n)).append('\n');
        sb.append('\n');
    }

    private static void block(StringBuilder sb, FileChange c) {
        sb.append("### ").append(sha(c.name())).append("\n\n");
        if (c.verdict() != null) sb.append("- verdict: ").append(c.verdict()).append('\n');
        if (c.statementType() != null) sb.append("- statement type: ").append(c.statementType()).append('\n');
        if (c.txnCount() != null) sb.append("- txn count: ").append(c.txnCount()).append('\n');
        for (String f : c.fieldChanges()) sb.append("- ").append(f).append('\n');
        if (c.rowsAdded() + c.rowsRemoved() + c.rowsChanged() > 0) {
            sb.append("- rows: ").append(c.rowsAdded()).append(" added, ").append(c.rowsRemoved()).append(" removed, ")
                    .append(c.rowsChanged()).append(" changed\n");
            examples(sb, "added", c.addedExamples());
            examples(sb, "removed", c.removedExamples());
            examples(sb, "changed", c.changedExamples());
        }
        sb.append('\n');
    }

    private static void examples(StringBuilder sb, String label, List<String> ex) {
        for (String e : ex) sb.append("    - ").append(label).append(' ').append(e).append('\n');
    }
}
