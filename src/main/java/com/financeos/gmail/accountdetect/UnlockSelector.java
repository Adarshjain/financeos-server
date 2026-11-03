package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks the password-protected statement mails worth unlocking by hand, all by rule:
 * <ol>
 *   <li>per entity with existence MEDIUM or better: its newest statement mail with a protected PDF ("newest");</li>
 *   <li>per such entity that is dormant or has a lifecycle (stopped, replaced, upgraded, add-on, closed): the last
 *   protected statement mail on or before its last activity ("last before stop") and one about 12 months earlier
 *   ("12 months before stop", within 45 days of that point);</li>
 *   <li>protected statement mails tied to no entity: the newest per (bank, layout cluster) ("unlinked");</li>
 *   <li>a mail with any PDF that already opened is skipped. Rows are unique per mail.</li>
 * </ol>
 * A mail is tied to an entity when the own numbers it names, among detected entities of its bank, are exactly one.
 * {@code ref} is opaque to this class (the caller's message reference); it never goes to a report or cache.
 */
public final class UnlockSelector {

    public static final String NEWEST = "newest";
    public static final String LAST_BEFORE_STOP = "last before stop";
    public static final String YEAR_BEFORE_STOP = "12 months before stop";
    public static final String UNLINKED = "unlinked";

    /** A statement-role mail with PDF outcomes; last4s are the numbers its derived slots name. */
    public record StatementMail(String ref, String bank, String clusterId, LocalDate date, Set<String> last4s,
                                boolean hasProtectedPdf, boolean hasOpenedPdf) {}

    public record Row(String ref, String bank, String last4, YearMonth month, String reason) {}

    private UnlockSelector() {}

    public static List<Row> select(List<StatementMail> mails, Detection d) {
        List<StatementMail> candidates = mails.stream()
                .filter(m -> m.hasProtectedPdf() && !m.hasOpenedPdf() && m.date() != null)
                .sorted(Comparator.comparing(StatementMail::date)).toList();
        Map<String, List<StatementMail>> byEntity = new LinkedHashMap<>();
        Map<String, StatementMail> unlinkedNewest = new LinkedHashMap<>();
        for (StatementMail m : candidates) {
            Set<String> hits = new TreeSet<>();
            for (String l4 : m.last4s()) if (d.find(m.bank(), l4) != null) hits.add(l4);
            if (hits.size() == 1) byEntity.computeIfAbsent(m.bank() + ":" + hits.iterator().next(), k -> new ArrayList<>()).add(m);
            else unlinkedNewest.put(m.bank() + "|" + m.clusterId(), m); // ascending order: the last put is the newest
        }
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Entity e : d.entities) {
            if (e.existsConf == null || !e.existsConf.atLeast(Conf.MEDIUM)) continue;
            List<StatementMail> ms = byEntity.getOrDefault(e.key(), List.of());
            if (ms.isEmpty()) continue;
            add(rows, ms.get(ms.size() - 1), e.last4, NEWEST);
            boolean stopped = e.status == Status.DORMANT || e.lifecycle != null;
            if (!stopped || e.last == null) continue;
            StatementMail lastBefore = null;
            for (StatementMail m : ms) if (!m.date().isAfter(e.last.plusDays(45))) lastBefore = m;
            if (lastBefore == null) continue;
            add(rows, lastBefore, e.last4, LAST_BEFORE_STOP);
            LocalDate target = lastBefore.date().minusMonths(12);
            StatementMail best = null;
            long bestGap = Long.MAX_VALUE;
            for (StatementMail m : ms) {
                long gap = Math.abs(ChronoUnit.DAYS.between(target, m.date()));
                if (gap <= 45 && gap < bestGap) {
                    best = m;
                    bestGap = gap;
                }
            }
            if (best != null) add(rows, best, e.last4, YEAR_BEFORE_STOP);
        }
        for (StatementMail m : unlinkedNewest.values()) add(rows, m, null, UNLINKED);
        return new ArrayList<>(rows.values());
    }

    private static void add(Map<String, Row> rows, StatementMail m, String last4, String reason) {
        rows.putIfAbsent(m.ref(), new Row(m.ref(), m.bank(), last4, YearMonth.from(m.date()), reason));
    }
}
