package com.financeos.gmail.accountdetect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Held-out gate: an independently written corpus (src/test/resources/accountdetect/heldout) fed as raw mails through
 * the real path (FeatureExtractor, StatementPdfReader on unlocked PDFs rendered with PDFBox, Detector, Stability).
 * dev/: per-field precision/recall with per-entity misses (made-up numbers); 0 wrong at HIGH and MEDIUM is asserted.
 * sealed/: aggregate counts only, never per-mail or per-entity detail, never asserted on.
 */
class HeldOutGateTest {

    static final Path ROOT = Path.of("src/test/resources/accountdetect/heldout");
    static final String[] FIELDS = {"exists", "kind", "variant", "lifecycle", "limit", "addon"};
    private static final Pattern ADDR = Pattern.compile("@([A-Za-z0-9.-]+)");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** limitKnown: the corpus states the limit truth (v2 "limitBucket", null = never stated); v1 has none. */
    record Expected(String key, Kind kind, String variant, Lifecycle lifecycle, String partner, String limit, boolean limitKnown, String addonOf) {}

    static final class Tally {
        final Map<String, int[]> prec = new TreeMap<>();     // field|conf -> {reported, correct}
        final Map<String, int[]> recall = new TreeMap<>();   // field -> {truth, >=MEDIUM, HIGH}
        final Map<String, int[]> issuerRecall = new TreeMap<>();   // bank -> {accounts, exists >=MEDIUM}
        final Map<String, Integer> counts = new TreeMap<>();
        final List<String> wrong = new ArrayList<>();
        final List<String> missed = new ArrayList<>();
        final List<String> lowWrong = new ArrayList<>();   // wrong at LOW: allowed, printed for dev only
        /** wrong variants at HIGH/MEDIUM by failure class and confidence: no names, numbers or mailboxes. */
        final Map<String, Integer> variantClass = new TreeMap<>();

        void p(String f, Conf c, boolean ok, String what) {
            int[] a = prec.computeIfAbsent(f + "|" + c, k -> new int[2]);
            a[0]++;
            if (ok) a[1]++;
            else if (c != Conf.LOW) wrong.add(f + " " + c + " " + what);
            else lowWrong.add(f + " " + what);
        }

        void r(String f, Conf found, String what) {
            int[] a = recall.computeIfAbsent(f, k -> new int[3]);
            a[0]++;
            if (found != null && found.atLeast(Conf.MEDIUM)) a[1]++;
            else missed.add(f + " " + what + " got " + found);
            if (found == Conf.HIGH) a[2]++;
        }

        void count(String k, int n) {
            counts.merge(k, n, Integer::sum);
        }
    }

    static String domainOf(String from) {
        Matcher m = ADDR.matcher(from == null ? "" : from);
        String d = null;
        while (m.find()) d = m.group(1);
        return d == null ? "" : d.toLowerCase();
    }

    static String bankKey(String issuerDomain, String last4) {
        String b = BankDirectory.bankForDomain(issuerDomain);
        return (b == null ? "?" + issuerDomain : b) + ":" + last4;
    }

    /** Standard-14 fonts encode WinAnsi only: anything else becomes a space. */
    static String winAnsi(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(ch >= 32 && ch < 127 ? ch : ' ');
        return sb.toString();
    }

    static byte[] render(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> lines = List.of(text.split("\\r?\\n"));
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < lines.size(); i += 45) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 9);
                    cs.newLineAtOffset(30, 760);
                    for (String l : lines.subList(i, Math.min(lines.size(), i + 45))) {
                        cs.showText(winAnsi(l));
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static Tally run(String split) throws IOException {
        return run(ROOT, split);
    }

    static Tally run(Path corpus, String split) throws IOException {
        Tally t = new Tally();
        ObjectMapper om = new ObjectMapper();
        List<Path> files;
        try (Stream<Path> s = Files.list(corpus.resolve(split))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        long seed = 1;
        for (Path f : files) {
            JsonNode root = om.readTree(f.toFile());
            String box = split.equals("dev") ? root.path("mailbox").asText() : "mailbox " + seed;
            List<MailDerived> derived = new ArrayList<>();
            List<StatementContent> statements = new ArrayList<>();
            for (JsonNode m : root.path("mails")) {
                String domain = domainOf(m.path("from").asText());
                LocalDate d = LocalDate.parse(m.path("date").asText().substring(0, 10));
                String body = m.path("body").asText("");
                String snippet = body.replaceAll("\\s+", " ").trim();
                if (snippet.length() > 150) snippet = snippet.substring(0, 150);
                List<String> names = new ArrayList<>();
                for (JsonNode a : m.path("attachments")) names.add(a.path("filename").asText(""));
                MailFeature mf = new MailFeature(domain, d.atTime(12, 0).atZone(IST).toInstant(), m.path("subject").asText(""), snippet, body, names);
                MailDerived md = FeatureExtractor.derive(mf);
                derived.add(md);
                for (JsonNode a : m.path("attachments")) {
                    if (!a.path("filename").asText("").toLowerCase().endsWith(".pdf")) continue;
                    t.count("pdf seen", 1);
                    if (a.path("protected").asBoolean(false) || !a.hasNonNull("pdfText")) {
                        t.count("pdf protected (skipped)", 1);
                        continue;
                    }
                    StatementPdfReader.Result r = StatementPdfReader.read(render(a.path("pdfText").asText()), "PDF_MAIL", md.bank(), YearMonth.from(d));
                    t.count("pdf " + r.status(), 1);
                    if (r.content() != null) statements.add(r.content().withOrigin(md.clusterId(), md.date()));
                }
            }
            Detection det = Stability.detectStable(derived, statements, seed++).detection();
            for (Detection.StatementLink l : det.statementLinks) t.count("statement " + l.outcome(), 1);

            Map<String, Expected> exp = new LinkedHashMap<>();
            for (JsonNode a : root.path("expected").path("accounts")) {
                String key = bankKey(a.path("issuerDomain").asText(), a.path("last4").asText());
                String lc = a.path("lifecycle").isNull() ? null : a.path("lifecycle").asText(null);
                Lifecycle l = lc == null ? null : lc.equals("ADDON") ? Lifecycle.ADD_ON : Lifecycle.valueOf(lc);
                String partner = a.path("lifecyclePartnerLast4").isNull() ? null
                        : bankKey(a.path("issuerDomain").asText(), a.path("lifecyclePartnerLast4").asText());
                // limit truth: v2 "limitBucket" (null = never stated, so any reported limit is wrong); v1 "limit" when
                // present; otherwise a reported limit is unscorable
                boolean limitKnown = a.has("limitBucket") || a.hasNonNull("limit");
                String limit = a.hasNonNull("limitBucket") ? a.path("limitBucket").asText() : a.hasNonNull("limit") ? a.path("limit").asText() : null;
                // v2 states addonOf; v1 names the primary of an ADDON in lifecyclePartnerLast4
                String addonOf = a.hasNonNull("addonOf") ? bankKey(a.path("issuerDomain").asText(), a.path("addonOf").asText())
                        : l == Lifecycle.ADD_ON ? partner : null;
                exp.put(key, new Expected(key, Kind.valueOf(a.path("kind").asText()),
                        a.path("variant").isNull() ? null : a.path("variant").asText(null), l, partner, limit, limitKnown, addonOf));
            }
            Map<String, Boolean> mustNot = new HashMap<>();
            for (JsonNode a : root.path("expected").path("mustNotReport")) mustNot.put(bankKey(a.path("issuerDomain").asText(), a.path("last4").asText()), true);

            String tag = split.equals("dev") ? box + " " : "";
            for (Entity e : det.entities) {
                Expected x = exp.get(e.key());
                String who = tag + e.key();
                t.p("exists", e.existsConf, x != null, who);
                if (mustNot.containsKey(e.key())) t.count("mustNotReport reported at " + e.existsConf, 1);
                if (e.kindConf != null) t.p("kind", e.kindConf, x != null && x.kind() == e.kind, who + " " + e.kind);
                if (e.variantConf != null) {
                    String bank = e.key().substring(0, e.key().indexOf(':'));
                    Boolean verdict = x == null ? Boolean.FALSE : variantVerdict(bank, x.variant(), e.variant);
                    // a truth label that is not a catalogue name of this issuer is neither a pass nor a wrong
                    if (verdict == null) t.count("variant unscorable (truth label not in catalogue) at " + e.variantConf, 1);
                    else t.p("variant", e.variantConf, verdict, who + " " + e.variant);
                    if (Boolean.FALSE.equals(verdict) && e.variantConf != Conf.LOW) {
                        List<String> others = exp.values().stream().filter(o -> o != x && o.key().startsWith(bank + ":") && o.variant() != null)
                                .map(Expected::variant).toList();
                        t.variantClass.merge(variantClass(bank, x == null ? null : x.variant(), x != null, e.variant, others) + " | " + e.variantConf, 1, Integer::sum);
                    }
                }
                if (e.limitConf != null) {
                    if (x == null || x.limitKnown()) t.p("limit", e.limitConf, x != null && Objects.equals(x.limit(), e.limitBucket), who + " " + e.limitBucket);
                    else t.count("limit reported without corpus truth at " + e.limitConf, 1);
                }
                if (e.lifecycleConf != null && e.lifecycle == Lifecycle.ADD_ON)
                    t.p("addon", e.lifecycleConf, x != null && x.addonOf() != null && x.addonOf().equals(e.linkedTo), who + " addonOf " + e.linkedTo);
                if (e.lifecycleConf != null && e.lifecycle != Lifecycle.STOPPED)
                    t.p("lifecycle", e.lifecycleConf, x != null && lifecycleOk(x, e), who + " " + e.lifecycle + "->" + e.linkedTo);
            }
            for (Expected x : exp.values()) {
                Entity e = det.find(x.key().substring(0, x.key().indexOf(':')), x.key().substring(x.key().indexOf(':') + 1));
                String who = tag + x.key();
                t.r("exists", e == null ? null : e.existsConf, who);
                int[] ir = t.issuerRecall.computeIfAbsent(x.key().substring(0, x.key().indexOf(':')), k -> new int[2]);
                ir[0]++;
                if (e != null && e.existsConf.atLeast(Conf.MEDIUM)) ir[1]++;
                if (x.addonOf() != null) t.r("addon", e == null || e.lifecycle != Lifecycle.ADD_ON || !x.addonOf().equals(e.linkedTo) ? null : e.lifecycleConf, who);
                t.r("kind", e == null || e.kind != x.kind() ? null : e.kindConf, who + " " + x.kind());
                if (x.variant() != null) {
                    String bank = x.key().substring(0, x.key().indexOf(':'));
                    if (ProductCatalog.canonical(bank, x.variant()) == null) t.count("variant truth unscorable (label not in catalogue)", 1);
                    else t.r("variant", e == null || !Boolean.TRUE.equals(variantVerdict(bank, x.variant(), e.variant)) ? null : e.variantConf, who + " " + x.variant());
                }
                if (x.limit() != null) t.r("limit", e == null || !x.limit().equals(e.limitBucket) ? null : e.limitConf, who + " " + x.limit());
                if (x.lifecycle() != null) t.r("lifecycle", e == null || e.lifecycleConf == null || !lifecycleOk(x, e) ? null : e.lifecycleConf, who + " " + x.lifecycle());
            }
            t.count("mailboxes", 1);
        }
        return t;
    }

    /**
     * Failure class of a wrong variant, for sealed diagnostics (no names leave this method): what the detector got
     * relative to the truth.
     */
    static String variantClass(String bank, String truth, boolean known, String got, List<String> otherTruths) {
        if (!known) return "entity not in truth";
        if (truth == null) return "expected null got a name";
        String ct = ProductCatalog.canonical(bank, truth), cg = ProductCatalog.canonical(bank, got);
        String tag = ct == null ? " (truth uncatalogued)" : "";
        for (String o : otherTruths) if (sameVariant(o, got) || (cg != null && cg.equals(ProductCatalog.canonical(bank, o)))) return "product of another card at the issuer" + tag;
        if (ProductCatalog.formerName(bank, truth) || ProductCatalog.formerName(bank, got)) return "older or renamed name" + tag;
        if (ct != null && ct.equals(cg)) {
            // which way the catalogue's canonical name differs from the label (word counts only, no names)
            String nt = ProductCatalog.key(bank, truth), ng = ProductCatalog.key(bank, got);
            int wt = nt.split(" ").length, wg = ng.split(" ").length;
            String how = nt.equals(ng) ? "same words, other spelling" : (" " + ng + " ").contains(" " + nt + " ") ? "label is a shorter part of the canonical"
                    : (" " + nt + " ").contains(" " + ng + " ") ? "canonical is a shorter part of the label" : "label is another alias (" + wt + " vs " + wg + " words)";
            return "alias of the expected entry: " + how + tag;
        }
        if (ProductCatalog.tierOnly(got)) return "tier word" + tag;
        if (cg == null) return "uncatalogued raw" + tag;
        String nt = ProductCatalog.norm(truth), ng = ProductCatalog.norm(got);
        if (nt.contains(ng) || ng.contains(nt)) {
            boolean gotLonger = ng.length() > nt.length();
            boolean words = ProductCatalog.nested(bank, ct, cg);
            return "nested catalogue products: reported is the " + (gotLonger ? "longer" : "shorter") + " name, "
                    + (words ? "word subset" : "text substring only") + tag;
        }
        return "other catalogued product" + tag;
    }

    /** Exact type and partner: REPLACED pairs with its REPLACEMENT (each names the other); UPGRADED is not REPLACED. */
    static boolean lifecycleOk(Expected x, Entity e) {
        if (x.lifecycle() == null) return false;
        return x.lifecycle() == e.lifecycle && Objects.equals(x.partner(), e.linkedTo);
    }

    /**
     * Fair variant scoring: truth label and reported name both go through the issuer's catalogue (aliases, former
     * names to the current one) and match when they resolve to one entry ("Regalia" is still not "Regalia Gold").
     * null = unscorable: the truth label is not a catalogue name of this issuer. A null truth is wrong for any name.
     */
    static Boolean variantVerdict(String bank, String truth, String reported) {
        if (truth == null || reported == null) return Boolean.FALSE;
        String ct = ProductCatalog.canonical(bank, truth);
        if (ct == null) return null;
        return ct.equals(ProductCatalog.canonical(bank, reported));
    }

    /** Exact product name; only case and whitespace are normalised ("Regalia" is not "Regalia Gold"). */
    static boolean sameVariant(String truth, String reported) {
        if (truth == null || reported == null) return false;
        return truth.trim().replaceAll("\\s+", " ").equalsIgnoreCase(reported.trim().replaceAll("\\s+", " "));
    }

    /** Wrong-fact counts by field and, for variants, by failure class: aggregates only. */
    static String classes(Tally t) {
        StringBuilder sb = new StringBuilder("wrong by field:");
        Map<String, Integer> byField = new TreeMap<>();
        for (String w : t.wrong) byField.merge(w.substring(0, w.indexOf(' ')) + " " + w.split(" ")[1], 1, Integer::sum);
        byField.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        sb.append("\nwrong variant classes:");
        t.variantClass.forEach((k, v) -> sb.append("\n  ").append(k).append(" = ").append(v));
        return sb.append('\n').toString();
    }

    static String table(Tally t) {
        StringBuilder sb = new StringBuilder("field | HIGH prec (n) | MEDIUM prec (n) | LOW prec (n) | recall>=MEDIUM | recall HIGH\n");
        for (String f : FIELDS) {
            sb.append(f);
            for (Conf c : Conf.values()) {
                int[] a = t.prec.getOrDefault(f + "|" + c, new int[2]);
                sb.append(" | ").append(a[0] == 0 ? "-" : String.format("%.1f%%", 100.0 * a[1] / a[0])).append(" (").append(a[0]).append(")");
            }
            int[] r = t.recall.getOrDefault(f, new int[3]);
            sb.append(" | ").append(r[0] == 0 ? "-" : String.format("%.1f%% of %d", 100.0 * r[1] / r[0], r[0]))
                    .append(" | ").append(r[0] == 0 ? "-" : String.format("%.1f%%", 100.0 * r[2] / r[0])).append('\n');
        }
        return sb.append("counts ").append(t.counts).append('\n').toString();
    }

    @Test
    void devGate_noWrongHighOrMedium() throws IOException {
        if (!Files.isDirectory(ROOT.resolve("dev"))) return;
        Tally t = run("dev");
        System.out.println("HELD-OUT dev\n" + table(t));
        t.wrong.forEach(w -> System.out.println("DEV WRONG " + w));
        t.missed.forEach(w -> System.out.println("DEV MISSED " + w));
        t.lowWrong.forEach(w -> System.out.println("DEV LOW-WRONG " + w));
        for (String f : FIELDS) {
            for (Conf c : List.of(Conf.HIGH, Conf.MEDIUM)) {
                int[] a = t.prec.getOrDefault(f + "|" + c, new int[2]);
                assertThat(a[1]).as("dev " + f + " " + c + " precision").isEqualTo(a[0]);
            }
        }
        assertThat(t.counts.getOrDefault("mustNotReport reported at HIGH", 0) + t.counts.getOrDefault("mustNotReport reported at MEDIUM", 0)).isZero();
        // an all-LOW detector can not pass: at least 80% of the real accounts must be reported at MEDIUM or better
        int[] r = t.recall.getOrDefault("exists", new int[3]);
        assertThat(r[1]).as("dev exists recall >= MEDIUM").isGreaterThanOrEqualTo((int) Math.ceil(0.8 * r[0]));
    }

    @Test
    void sealedAggregatesOnly() throws IOException {
        if (!Files.isDirectory(ROOT.resolve("sealed"))) return;
        Tally t = run("sealed");
        int wrongHM = t.wrong.size();
        int mustNot = t.counts.getOrDefault("mustNotReport reported at HIGH", 0) + t.counts.getOrDefault("mustNotReport reported at MEDIUM", 0);
        System.out.println("HELD-OUT sealed (aggregates only)\n" + table(t) + "wrong at HIGH/MEDIUM: " + wrongHM + "\n" + classes(t));
        // the failure message carries counts only, never which mailbox or number
        assertThat(wrongHM).as("sealed wrong facts at HIGH/MEDIUM (count only)").isZero();
        assertThat(mustNot).as("sealed mustNotReport at HIGH/MEDIUM (count only)").isZero();
    }
}
