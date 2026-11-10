package com.financeos.gmail.accountdetect;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bootstrap stability: re-run detection on resamples keeping a random 70% of mails and statements, and demote
 * every HIGH fact reported in fewer than 95% of them to MEDIUM. Part of the HIGH contract.
 */
public final class Stability {

    public static final int RESAMPLES = 20;
    public static final double KEEP = 0.70;
    public static final double HIGH_MIN = 0.95;

    public record Result(Detection detection, Map<String, Double> stability, int demoted) {}

    private Stability() {}

    public static Result detectStable(List<MailDerived> mails, List<StatementContent> statements, long seed) {
        return detectStable(mails, statements, seed, null);
    }

    /** As the 3-arg form; when {@code timings} is not null it gets "10 detect full" and "11 stability resamples" in ms. */
    public static Result detectStable(List<MailDerived> mails, List<StatementContent> statements, long seed, Map<String, Long> timings) {
        return detectStable(mails, statements, seed, timings, Detector.Options.ALL);
    }

    /** As the 4-arg form, detecting with {@code opt} (the full run and every resample). */
    public static Result detectStable(List<MailDerived> mails, List<StatementContent> statements, long seed, Map<String, Long> timings, Detector.Options opt) {
        long t0 = System.nanoTime();
        Detection full = Timing.call(Ops.ACCOUNTDETECT_DETECT_FULL, () -> Detector.detect(mails, statements, opt));
        Map<String, Conf> fullFacts = full.facts();
        long t1 = System.nanoTime();
        Map<String, Integer> hits = new HashMap<>();
        Random rnd = new Random(seed);
        Timing.run(Ops.ACCOUNTDETECT_RESAMPLES, () -> {
            for (int r = 0; r < RESAMPLES; r++) {
                List<MailDerived> m = new ArrayList<>();
                for (MailDerived d : mails) if (rnd.nextDouble() < KEEP) m.add(d);
                List<StatementContent> s = new ArrayList<>();
                for (StatementContent c : statements) if (rnd.nextDouble() < KEEP) s.add(c);
                // a resample supports a fact only when it reports it at the full run's confidence or higher
                for (var f : Detector.detect(m, s, opt).facts().entrySet()) {
                    Conf at = fullFacts.get(f.getKey());
                    if (at != null && f.getValue().atLeast(at)) hits.merge(f.getKey(), 1, Integer::sum);
                }
            }
        });
        if (timings != null) {
            timings.put("10 detect full", (t1 - t0) / 1_000_000);
            timings.put("11 stability resamples", (System.nanoTime() - t1) / 1_000_000);
        }
        Map<String, Double> stab = new HashMap<>();
        for (String f : fullFacts.keySet()) stab.put(f, hits.getOrDefault(f, 0) / (double) RESAMPLES);
        int demoted = 0;
        for (Entity e : full.entities) {
            if (e.existsConf == Conf.HIGH && stab.get("E|" + e.key()) < HIGH_MIN) {
                e.existsConf = Conf.MEDIUM;
                demoted++;
            }
            if (e.kindConf == Conf.HIGH && stab.get("K|" + e.key() + "|" + e.kind) < HIGH_MIN) {
                e.kindConf = Conf.MEDIUM;
                demoted++;
            }
            if (e.variantConf == Conf.HIGH && stab.get("V|" + e.key() + "|" + e.variant) < HIGH_MIN) {
                e.variantConf = Conf.MEDIUM;
                demoted++;
            }
            if (e.lifecycleConf == Conf.HIGH && stab.get("L|" + e.key() + "|" + e.lifecycle + "|" + e.linkedTo) < HIGH_MIN) {
                e.lifecycleConf = Conf.MEDIUM;
                demoted++;
            }
        }
        // a demoted fact still has to meet MEDIUM's rule, and every fact is re-capped at its (and its partner's)
        // existence after the demotions; then the whole output is checked
        Contract.enforce(full);
        Contract.verify(full);
        if (demoted > 0) full.dropped.merge("HIGH demoted to MEDIUM (bootstrap < 95%)", demoted, Integer::sum);
        return new Result(full, stab, demoted);
    }
}
