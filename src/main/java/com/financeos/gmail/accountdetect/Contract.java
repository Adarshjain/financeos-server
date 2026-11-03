package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The confidence contract, in one place. {@link #enforce} lowers every fact to what its evidence allows;
 * {@link #verify} throws when any reported fact is above that. Both run after detection and again after the
 * bootstrap ({@link Stability}).
 *
 * <ul>
 *   <li>MEDIUM (any fact): at least 2 mails/documents spanning at least {@value #MIN_SPAN_DAYS} days.</li>
 *   <li>HIGH existence: MEDIUM's rule, at least 2 bank-side channels (payment apps never count), never in a
 *   counterparty slot. MEDIUM existence: no counterparty sighting, or 2 bank channels, or one channel whose own-slot
 *   mails outnumber its counterparty sightings at least 20 to 1 (and number at least 20). Statement-document-only or single-layout (local) numbers are LOW, and so is any number
 *   from a sender outside the issuer directory (the issuer itself is unknown). A number the bank's own statement
 *   header names is kept whatever the mail slots say: LOW from 1 statement, MEDIUM from 2 spanning
 *   {@value #MIN_SPAN_DAYS}+ days, never HIGH on that alone.</li>
 *   <li>HIGH kind: MEDIUM's rule, 100% agreement, on at least 2 channels.</li>
 *   <li>HIGH variant: MEDIUM's rule on at least 2 channels; a variant exists only on a credit card.</li>
 *   <li>Lifecycle: HIGH needs at least 2 independent explicit witnesses (a mail and its PDF are one) spanning
 *   {@value #MIN_SPAN_DAYS}+ days; MEDIUM needs that, or 1 explicit witness plus a corroborating activity timeline; a
 *   single mail alone, a timeline alone, a block, STOPPED, or a fact resting only on period-linked statements of a bank
 *   with several own numbers of that kind is LOW. An upgrade inferred from product names is capped by both names.</li>
 *   <li>A product name with bank words after the dictionary name ("Airtel Axis Bank") is LOW: whether they are part
 *   of the name is unknown.</li>
 *   <li>Limit: MEDIUM at most, needing 2+ header-region statements spanning {@value #MIN_SPAN_DAYS}+ days that agree.</li>
 *   <li>Existence of an account seen only in debit/transfer layouts (never in a balance or statement layout) is LOW.</li>
 *   <li>Every fact is capped at its entity's existence, and a linked fact also at its partner's existence.</li>
 * </ul>
 */
public final class Contract {

    public static final long MIN_SPAN_DAYS = 30;
    static final int CP_RATIO = 20, CP_RATIO_MIN_MAILS = 20;

    private Contract() {}

    private static boolean medium(int mails, long span) {
        return mails >= 2 && span >= MIN_SPAN_DAYS;
    }

    static Conf existsMax(Entity e) {
        Conf c = mailExistsMax(e);
        // the bank's own statement header names it as the holder's number: 2 such statements spanning 30+ days are
        // MEDIUM whatever the mail slots say (a counterparty sighting still rules out HIGH); 1 statement is LOW
        if (c == Conf.LOW && medium(e.headerStatements, e.headerSpanDays) && !Detector.placeholder(e.last4)
                && e.bank != null && !e.bank.startsWith("?")) return Conf.MEDIUM;
        return c;
    }

    private static Conf mailExistsMax(Entity e) {
        if (e.statementOnly || e.localOnly || !medium(e.evidenceMails, e.evidenceSpanDays)) return Conf.LOW;
        // a sample-shaped number ("1234") is only ever kept as a statement header number, and never above LOW
        if (Detector.placeholder(e.last4)) return Conf.LOW;
        // a number only in debit/transfer layouts (a payee) is never an own account above LOW
        if (e.transferOnly && (e.kind == null || e.kind == Kind.ACCOUNT)) return Conf.LOW;
        if (e.bank == null || e.bank.startsWith("?")) return Conf.LOW;   // sender not in the issuer directory: the issuer is unknown
        if (e.bankChannels.size() >= 2 && e.counterpartyHits == 0) return Conf.HIGH;
        if (e.counterpartyHits == 0 || e.bankChannels.size() >= 2) return Conf.MEDIUM;
        // one bank channel with a few counterparty sightings (an own card named as the payee of its own bill payment):
        // MEDIUM only when own-slot mails outnumber them at least 20 to 1
        if (e.evidenceMails >= CP_RATIO_MIN_MAILS && (long) e.counterpartyHits * CP_RATIO <= e.evidenceMails) return Conf.MEDIUM;
        return Conf.LOW;
    }

    static Conf kindMax(Entity e) {
        if (!medium(e.kindMails, e.kindSpanDays)) return Conf.LOW;
        return e.kindUnanimous && e.kindChannels >= 2 ? Conf.HIGH : Conf.MEDIUM;
    }

    static Conf variantMax(Entity e) {
        if (e.variantWeakLink || e.variantAmbiguous || !medium(e.variantMails, e.variantSpanDays)) return Conf.LOW;
        return e.variantChannels >= 2 ? Conf.HIGH : Conf.MEDIUM;
    }

    static Conf lifecycleMax(Entity e, Map<String, Entity> byKey) {
        if (e.lifecycle == Lifecycle.STOPPED || e.lifecycleFromBlock || e.lifecycleWeakLink) return Conf.LOW;
        int n = e.lifecycleWitnesses.size();
        long span = n < 2 ? 0 : java.time.temporal.ChronoUnit.DAYS.between(e.lifecycleWitnesses.first(), e.lifecycleWitnesses.last());
        Conf c = n >= 2 && span >= MIN_SPAN_DAYS ? Conf.HIGH : n >= 1 && e.lifecycleTimeline ? Conf.MEDIUM : Conf.LOW;
        c = c.min(e.existsConf);
        if (e.linkedTo != null) {
            Entity p = byKey.get(e.linkedTo);
            c = p == null || p.existsConf == null ? Conf.LOW : c.min(p.existsConf);
            if (e.lifecycleFromVariants) {
                c = e.variantConf == null || p == null || p.variantConf == null ? Conf.LOW : c.min(e.variantConf).min(p.variantConf);
            }
        }
        return c;
    }

    static Conf limitMax(Entity e) {
        if (e.limitFromMail || e.limitWeakLink || !e.limitAgree || !medium(e.limitDocs, e.limitSpanDays)) return Conf.LOW;
        return Conf.MEDIUM;
    }

    /** Lowers every fact to its contract maximum. Idempotent. */
    public static void enforce(Detection d) {
        Map<String, Entity> byKey = new HashMap<>();
        for (Entity e : d.entities) byKey.put(e.key(), e);
        for (Entity e : d.entities) e.existsConf = e.existsConf == null ? existsMax(e) : e.existsConf.min(existsMax(e));
        for (Entity e : d.entities) {
            if (e.kindConf != null) e.kindConf = e.kindConf.min(kindMax(e)).min(e.existsConf);
            if (e.variant != null && e.kind != Kind.CREDIT_CARD) {
                e.variant = null;
                e.variantConf = null;
            }
            if (e.variantConf != null) e.variantConf = e.variantConf.min(variantMax(e)).min(e.existsConf);
            if (e.limitConf != null) e.limitConf = e.limitConf.min(limitMax(e)).min(e.existsConf);
        }
        // lifecycle last, twice so a partner's lowered fact settles
        for (int i = 0; i < 2; i++) {
            for (Entity e : d.entities) if (e.lifecycleConf != null) e.lifecycleConf = e.lifecycleConf.min(lifecycleMax(e, byKey));
        }
    }

    /** Every contract breach in a detection (empty when it holds). */
    public static List<String> violations(Detection d) {
        Map<String, Entity> byKey = new HashMap<>();
        for (Entity e : d.entities) byKey.put(e.key(), e);
        List<String> v = new ArrayList<>();
        for (Entity e : d.entities) {
            String k = e.key();
            if (e.existsConf == null) v.add(k + " exists without confidence");
            else if (!existsMax(e).atLeast(e.existsConf)) v.add(k + " exists " + e.existsConf + " > " + existsMax(e));
            if (e.kindConf != null && (!kindMax(e).atLeast(e.kindConf) || !e.existsConf.atLeast(e.kindConf))) v.add(k + " kind " + e.kindConf);
            if (e.variantConf != null && (e.kind != Kind.CREDIT_CARD || !variantMax(e).atLeast(e.variantConf) || !e.existsConf.atLeast(e.variantConf)))
                v.add(k + " variant " + e.variantConf);
            if (e.lifecycleConf != null && !lifecycleMax(e, byKey).atLeast(e.lifecycleConf)) v.add(k + " lifecycle " + e.lifecycleConf);
            if (e.limitConf != null && (!limitMax(e).atLeast(e.limitConf) || !e.existsConf.atLeast(e.limitConf))) v.add(k + " limit " + e.limitConf);
        }
        return v;
    }

    /** Throws if any reported fact breaks the contract. */
    public static void verify(Detection d) {
        List<String> v = violations(d);
        if (!v.isEmpty()) throw new IllegalStateException("confidence contract violated: " + v.size() + " " + v.subList(0, Math.min(5, v.size())));
    }
}
