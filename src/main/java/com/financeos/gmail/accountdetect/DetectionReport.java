package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders the detection table and the truth-free checks (V2). Output: banks, masked last4, kinds, counts, yyyy-mm only. */
public final class DetectionReport {

    private DetectionReport() {}

    public static final String CONTRACT = """
            Confidence contract:
              HIGH   = >=2 independent channels agree (billing mails / alerts / service mails / statement content / payment app),
                       zero contradictions (never in a counterparty slot, 100% kind agreement, one product name),
                       and the fact holds in >=95% of 20 bootstrap resamples (70% of mails); otherwise demoted.
              MEDIUM = one strong channel repeated (>=2 mails over >=2 months).
              LOW    = inferred (status always; single layout; timeline-only stopped).
              below  = not reported; counted in the dropped tally.
              A fact never exceeds its entity's existence confidence.""";

    public static String table(Detection d) {
        StringBuilder sb = new StringBuilder();
        sb.append("bank | last4 | exists | kind | kind_conf | variant | variant_conf | lifecycle | lifecycle_conf | linked_to | status(LOW) | first | last | channels | limit\n");
        for (Entity e : d.entities) {
            sb.append(e.bank).append(" | ").append(e.last4).append(" | ").append(e.existsConf).append(" | ")
                    .append(s(e.kindConf == null ? null : e.kind)).append(" | ").append(s(e.kindConf)).append(" | ")
                    .append(e.variantConf == null ? (e.variantConflicts > 0 ? "(conflict x" + e.variantConflicts + ")" : "-") : ProductCatalog.printable(e.bank, e.variant)).append(" | ")
                    .append(s(e.variantConf)).append(" | ").append(s(e.lifecycleConf == null ? null : e.lifecycle)).append(" | ")
                    .append(s(e.lifecycleConf)).append(" | ").append(s(e.linkedTo)).append(" | ").append(s(e.status)).append(" | ")
                    .append(e.first == null ? "-" : YearMonth.from(e.first)).append(" | ").append(e.last == null ? "-" : YearMonth.from(e.last)).append(" | ")
                    .append(e.channels.stream().map(Enum::name).collect(Collectors.joining("+"))).append(" | ")
                    .append(e.limitBucket == null ? "-" : e.limitBucket + "(" + e.limitConf + ")").append('\n');
        }
        return sb.toString();
    }

    private static String s(Object o) {
        return o == null ? "-" : o.toString();
    }

    public static String dropped(Detection d) {
        StringBuilder sb = new StringBuilder("Dropped (not reported), by reason:\n");
        d.dropped.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    /** V2 checks on a stable detection. */
    public static String checks(Stability.Result r, List<MailDerived> mails, List<StatementContent> statements) {
        Detection d = r.detection();
        StringBuilder sb = new StringBuilder();
        // (a) bootstrap
        long highFacts = 0, highUnstable = 0;
        Map<String, Conf> facts = d.facts();
        for (var f : facts.entrySet()) {
            if (f.getValue() != Conf.HIGH) continue;
            highFacts++;
            if (r.stability().getOrDefault(f.getKey(), 0.0) < Stability.HIGH_MIN) highUnstable++;
        }
        double minHigh = facts.entrySet().stream().filter(f -> f.getValue() == Conf.HIGH)
                .mapToDouble(f -> r.stability().getOrDefault(f.getKey(), 0.0)).min().orElse(1.0);
        sb.append("V2a bootstrap: resamples=").append(Stability.RESAMPLES).append(" keep=").append(Stability.KEEP)
                .append(", HIGH facts=").append(highFacts).append(", HIGH facts below 95% after demotion=").append(highUnstable)
                .append(", min HIGH stability=").append(String.format("%.2f", minHigh)).append(", demoted=").append(r.demoted()).append('\n');
        // (b) channel holdout
        Detection alerts = Detector.detect(mails, statements, new Detector.Options(Set.of(Role.TXN_ALERT), false));
        Detection billing = Detector.detect(mails, statements, new Detector.Options(Set.of(Role.STATEMENT, Role.DUE_REMINDER), false));
        sb.append("V2b holdout alerts-only vs billing-only: ").append(agreement(alerts, billing)).append('\n');
        if (!statements.isEmpty()) {
            Detection content = Detector.detect(List.of(), statements);
            sb.append("V2b holdout billing-mails vs statement-content (last4 read from the document only): ").append(agreement(billing, content)).append('\n');
        }
        // (c) contradictions
        long highCp = d.entities.stream().filter(e -> e.existsConf == Conf.HIGH && e.counterpartyHits > 0).count();
        sb.append("V2c contradictions: own numbers ever seen in a counterparty slot=").append(d.ownSeenAsCounterparty.size())
                .append(" (reported at HIGH: ").append(highCp).append(")\n");
        // (d) cadence
        List<String> exc = new ArrayList<>();
        int highCc = 0;
        for (Entity e : d.entities) {
            if (e.existsConf != Conf.HIGH || e.kind != Kind.CREDIT_CARD || e.lifecycle == Vocab.Lifecycle.ADD_ON) continue;
            highCc++;
            if (e.billingMonths.size() < 2) {
                exc.add(e.key() + " no billing series");
                continue;
            }
            long span = ChronoUnit.MONTHS.between(e.billingMonths.first(), e.billingMonths.last()) + 1;
            long missing = span - e.billingMonths.size();
            if (missing > Math.max(1, span / 6)) exc.add(e.key() + " " + missing + " of " + span + " months missing");
        }
        sb.append("V2d cadence: HIGH credit cards (non add-on)=").append(highCc).append(", exceptions=").append(exc.size())
                .append(exc.isEmpty() ? "" : " " + exc).append('\n');
        // (e) payment-app agreement
        int appKeys = d.appHits.size(), matched = 0;
        for (String k : d.appHits.keySet()) {
            int i = k.indexOf(':');
            if (d.find(k.substring(0, i), k.substring(i + 1)) != null) matched++;
        }
        sb.append("V2e payment-app agreement: app-named cards=").append(appKeys).append(", matching a detected entity=").append(matched)
                .append(appKeys == 0 ? "" : String.format(" (%.0f%%)", 100.0 * matched / appKeys)).append('\n');
        return sb.toString();
    }

    /** Statement documents by source and link outcome, then one line per linked document: bank, last4, yyyy-mm. */
    public static String statementLinks(Detection d) {
        Map<String, Integer> outcomes = new java.util.TreeMap<>();
        Map<String, Integer> linked = new java.util.TreeMap<>();
        for (Detection.StatementLink l : d.statementLinks) {
            outcomes.merge(l.source() + " " + l.outcome(), 1, Integer::sum);
            if (l.last4() != null) linked.merge(l.source() + " | " + l.outcome() + " | " + l.bank() + " | " + l.last4() + " | "
                    + (l.period() == null ? "-" : l.period()), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Statement links by source and outcome: ").append(outcomes).append('\n');
        sb.append("Linked statements (source | outcome | bank | last4 | yyyy-mm | documents):\n");
        linked.forEach((k, v) -> sb.append("  ").append(k).append(" | ").append(v).append('\n'));
        return sb.toString();
    }

    static String agreement(Detection a, Detection b) {
        Set<String> ka = new HashSet<>(), kb = new HashSet<>();
        a.entities.forEach(e -> ka.add(e.key()));
        b.entities.forEach(e -> kb.add(e.key()));
        Set<String> both = new HashSet<>(ka);
        both.retainAll(kb);
        Set<String> union = new HashSet<>(ka);
        union.addAll(kb);
        int kindBoth = 0, kindAgree = 0;
        for (String k : both) {
            int i = k.indexOf(':');
            Entity x = a.find(k.substring(0, i), k.substring(i + 1)), y = b.find(k.substring(0, i), k.substring(i + 1));
            if (x.kindConf != null && y.kindConf != null) {
                kindBoth++;
                if (x.kind == y.kind) kindAgree++;
            }
        }
        return "A=" + ka.size() + " B=" + kb.size() + " both=" + both.size() + " jaccard=" + (union.isEmpty() ? "-" : String.format("%.2f", both.size() / (double) union.size()))
                + ", kinds compared=" + kindBoth + " agree=" + kindAgree;
    }
}
