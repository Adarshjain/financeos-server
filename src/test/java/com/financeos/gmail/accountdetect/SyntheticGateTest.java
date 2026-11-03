package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.SyntheticMailbox.Truth;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 gate over 30 seeded synthetic mailboxes: HIGH precision 100% and MEDIUM precision >= 95% for every field
 * (exists, kind, variant, lifecycle); recall is printed per field.
 */
class SyntheticGateTest {

    static final int MAILBOXES = 30;
    static final String[] FIELDS = {"exists", "kind", "variant", "lifecycle", "limit"};

    /** [field][conf] -> {reported, correct}; recall: [field] -> {truth, found >= MEDIUM, found HIGH}. */
    static final class Tally {
        final Map<String, int[]> prec = new LinkedHashMap<>();
        final Map<String, int[]> recall = new LinkedHashMap<>();
        final List<String> wrong = new ArrayList<>();
        final List<String> missed = new ArrayList<>();

        void p(String field, Conf c, boolean ok, String what) {
            int[] a = prec.computeIfAbsent(field + "|" + c, k -> new int[2]);
            a[0]++;
            if (ok) a[1]++;
            else if (c != Conf.LOW) wrong.add(field + " " + c + " " + what);
        }

        void r(String field, Conf found) {
            int[] a = recall.computeIfAbsent(field, k -> new int[3]);
            a[0]++;
            if (found != null && found.atLeast(Conf.MEDIUM)) a[1]++;
            if (found == Conf.HIGH) a[2]++;
        }
    }

    static Tally evaluate(int from, int to) {
        Tally t = new Tally();
        for (long seed = from; seed <= to; seed++) {
            SyntheticMailbox mb = SyntheticMailbox.generate(seed);
            List<MailDerived> derived = mb.mails.stream().map(FeatureExtractor::derive).toList();
            Detection d = Stability.detectStable(derived, mb.statements, seed).detection();
            score(t, mb, d, "seed " + seed);
        }
        return t;
    }

    static void score(Tally t, SyntheticMailbox mb, Detection d, String tag) {
        for (Entity e : d.entities) {
            Truth tr = mb.truth.get(e.key());
            t.p("exists", e.existsConf, tr != null, tag + " " + e.key());
            if (e.kindConf != null) t.p("kind", e.kindConf, tr != null && tr.kind() == e.kind, tag + " " + e.key() + " " + e.kind);
            if (e.variantConf != null) t.p("variant", e.variantConf, tr != null && variantMatches(tr.variant(), e.variant), tag + " " + e.key() + " " + e.variant);
            if (e.limitConf != null) t.p("limit", e.limitConf, tr != null && SyntheticMailbox.limitOf(e.last4).equals(e.limitBucket), tag + " " + e.key() + " " + e.limitBucket);
            if (e.lifecycleConf != null && e.lifecycle != Lifecycle.STOPPED) {
                boolean ok = tr != null && lifecycleMatches(tr, e);
                t.p("lifecycle", e.lifecycleConf, ok, tag + " " + e.key() + " " + e.lifecycle + "->" + e.linkedTo + " truth " + (tr == null ? "-" : tr.lifecycle()));
            }
        }
        for (Truth tr : mb.truth.values()) {
            Entity e = d.find(tr.bank(), tr.last4());
            t.r("exists", e == null ? null : e.existsConf);
            t.r("kind", e == null || e.kind != tr.kind() ? null : e.kindConf);
            boolean hasStatements = mb.statements.stream().anyMatch(s -> tr.bank().equals(s.bank()) && tr.last4().equals(s.last4()) && s.limitBucket() != null);
            if (hasStatements) t.r("limit", e == null || !SyntheticMailbox.limitOf(tr.last4()).equals(e.limitBucket) ? null : e.limitConf);
            if (tr.variant() != null) t.r("variant", e == null || !variantMatches(tr.variant(), e.variant) ? null : e.variantConf);
            if (tr.lifecycle() != null) {
                Conf c = e == null || e.lifecycleConf == null || !lifecycleMatches(tr, e) ? null : e.lifecycleConf;
                t.r("lifecycle", c);
                if (c == null || c == Conf.LOW) t.missed.add(tag + " " + tr.key() + " truth " + tr.lifecycle() + "->" + tr.linkedTo() + " got "
                        + (e == null ? "no entity" : e.existsConf + " " + e.lifecycle + "/" + e.lifecycleConf + "->" + e.linkedTo + " kind " + e.kind + " billing " + e.billingMonths.size() + " ch " + e.channels));
            }
        }
    }

    /** The generator's product name starts with the reported dictionary name, word by word ("Infinia Metal" / "Infinia"). */
    static boolean variantMatches(String truth, String reported) {
        if (truth == null || reported == null) return false;
        String t = " " + truth.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim() + " ";
        String r = " " + reported.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim() + " ";
        return t.startsWith(r);
    }

    /** Exact type and partner; UPGRADED is not REPLACED. The successor side of a pair is REPLACEMENT. */
    static boolean lifecycleMatches(Truth tr, Entity e) {
        if (tr.lifecycle() == null) return false;
        boolean type = tr.lifecycle() == e.lifecycle;
        return type && java.util.Objects.equals(tr.linkedTo(), e.linkedTo);
    }

    static String render(Tally t) {
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
        return sb.toString();
    }

    @Test
    void gate_highIsExact_mediumAtLeast95() {
        Tally t = evaluate(1, MAILBOXES);
        System.out.println("V1 synthetic gate (" + MAILBOXES + " mailboxes)\n" + render(t));
        t.wrong.stream().limit(40).forEach(w -> System.out.println("WRONG " + w));
        t.missed.stream().limit(60).forEach(w -> System.out.println("MISSED lifecycle " + w));
        for (String f : FIELDS) {
            int[] h = t.prec.getOrDefault(f + "|HIGH", new int[2]);
            assertThat(h[1]).as(f + " HIGH precision").isEqualTo(h[0]);
            int[] m = t.prec.getOrDefault(f + "|MEDIUM", new int[2]);
            assertThat(m[1]).as(f + " MEDIUM precision").isGreaterThanOrEqualTo((int) Math.ceil(0.95 * m[0]));
        }
    }
}
