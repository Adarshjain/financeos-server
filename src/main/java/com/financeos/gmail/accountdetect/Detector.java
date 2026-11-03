package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Detection.Entity;
import com.financeos.gmail.accountdetect.MailDerived.AppHit;
import com.financeos.gmail.accountdetect.MailDerived.EventHit;
import com.financeos.gmail.accountdetect.MailDerived.Slot;
import com.financeos.gmail.accountdetect.MailDerived.VariantHit;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.Vocab.Role;
import com.financeos.gmail.accountdetect.Vocab.Status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic account detector over derived mail features and statement content.
 *
 * <p>Confidence contract (also in the report):
 * <ul>
 *   <li>HIGH: at least two independent channels agree (billing mails, alerts, card/account service mails, statement
 *   content, payment app), zero contradictions (never seen in a counterparty slot, 100% kind agreement, one product
 *   name), and the fact survives at least 95% of 70% bootstrap resamples ({@link Stability}).</li>
 *   <li>MEDIUM: one strong channel repeated (two or more mails over two or more months).</li>
 *   <li>LOW: inferred (status always; a single layout; timeline-only "stopped").</li>
 *   <li>Anything weaker is not reported; it is counted in {@link Detection#dropped} by reason.</li>
 * </ul>
 * A fact's confidence never exceeds its entity's existence confidence.
 */
public final class Detector {

    /** Optional restriction for channel-holdout runs: only these roles, and statement content on/off. */
    public record Options(Set<Role> roles, boolean statementContent) {
        public static final Options ALL = new Options(null, true);
    }

    public static final String LINK_LAST4 = "linked by last4";
    public static final String LINK_PERIOD = "linked by period";
    public static final String LINK_ORIGIN = "linked by its mail";
    public static final String LINK_NONE = "unlinked";
    public static final String LINK_NOT_STATEMENT = "not a statement";
    public static final String LINK_SAME_AS_MAIL = "same statement as a mail PDF";

    enum SlotClass { OWN, LOCAL, COUNTERPARTY, MIXED, UNSCORED }

    private record Occ(String bank, String last4, String slotKey, LabelKind label, String cluster, Role role, Kind clusterKind,
                       LocalDate date, int mail) {}

    private static final class SlotStat {
        final Map<String, Set<Integer>> mailsByValue = new LinkedHashMap<>();
        final Set<Integer> mails = new HashSet<>();
        final String key;
        final String cluster;
        SlotClass cls;

        SlotStat(String key, String cluster) {
            this.key = key;
            this.cluster = cluster;
        }
    }

    private static final class Ev {
        final Set<Channel> channels = EnumSet.noneOf(Channel.class);
        final Set<String> clusters = new HashSet<>();
        final TreeSet<YearMonth> months = new TreeSet<>();
        final TreeSet<YearMonth> billing = new TreeSet<>();
        final TreeSet<YearMonth> alertMonths = new TreeSet<>();
        final Map<Kind, Set<Channel>> kindChannels = new EnumMap<>(Kind.class);
        final Map<Kind, Set<YearMonth>> kindMonths = new EnumMap<>(Kind.class);
        final Map<Kind, Integer> kindVotes = new EnumMap<>(Kind.class);
        final Map<Kind, TreeSet<LocalDate>> kindDates = new EnumMap<>(Kind.class);
        final Set<Object> evidence = new HashSet<>();   // distinct mails / documents
        final Set<Channel> bankChannels = EnumSet.noneOf(Channel.class);
        final TreeSet<LocalDate> evidenceDates = new TreeSet<>();
        int otherKindVotes;
        int strong;
        int local;
        /** LOCAL sightings labelled as the user's own card ("your Debit Card XX1234"), and the rest. */
        int localCard;
        int localOther;
        /** Named by a statement document (its own number or an add-on it lists), not only by mail text. */
        boolean doc;
        /** layout cluster | date of each bank mail whose text names the number. */
        final Set<String> mailSightings = new HashSet<>();
        int mentions;
        int counterparty;
        int transferMails;
        int plainMails;
        boolean balanceSeen;
        LocalDate first;
        LocalDate last;

        void seen(LocalDate d) {
            if (d == null) return;
            months.add(YearMonth.from(d));
            if (first == null || d.isBefore(first)) first = d;
            if (last == null || d.isAfter(last)) last = d;
        }

        void vote(Kind k, Channel ch, LocalDate d) {
            if (k == null) return;
            kindVotes.merge(k, 1, Integer::sum);
            kindChannels.computeIfAbsent(k, x -> EnumSet.noneOf(Channel.class)).add(ch);
            if (d != null) {
                kindMonths.computeIfAbsent(k, x -> new TreeSet<>()).add(YearMonth.from(d));
                kindDates.computeIfAbsent(k, x -> new TreeSet<>()).add(d);
            }
        }

        void evidence(Object source, Channel ch, LocalDate d) {
            evidence.add(source);
            bankChannels.add(ch);
            if (d != null) evidenceDates.add(d);
        }
    }

    private Detector() {}

    public static Detection detect(List<MailDerived> mails, List<StatementContent> statements) {
        return detect(mails, statements, Options.ALL);
    }

    public static Detection detect(List<MailDerived> mails, List<StatementContent> statements, Options opt) {
        Detection out = new Detection();
        if (!opt.statementContent()) statements = List.of();
        List<StatementContent> docs = new ArrayList<>();
        // a folder/DB document that is the same statement (bank + last4 + period) as a mail's PDF counts once: as the mail's
        Set<String> mailDocs = new HashSet<>();
        for (StatementContent s : statements) if ((s.fromMail() || "PDF_MAIL".equals(s.source())) && s.kind() != null && s.last4() != null && s.period() != null)
            mailDocs.add(s.bank() + ":" + s.last4() + ":" + s.period());
        for (StatementContent s : statements) {
            if (s.kind() == null) out.statementLinks.add(new Detection.StatementLink(s.source(), LINK_NOT_STATEMENT, s.bank(), null, s.period(), s));
            else if (!s.fromMail() && !"PDF_MAIL".equals(s.source()) && s.last4() != null && mailDocs.contains(s.bank() + ":" + s.last4() + ":" + s.period()))
                out.statementLinks.add(new Detection.StatementLink(s.source(), LINK_SAME_AS_MAIL, s.bank(), s.last4(), s.period(), s));
            else docs.add(s);
        }
        statements = docs;

        // ---- 1. cluster roles by majority vote
        Map<String, Role> clusterRole = clusterRoles(mails);
        for (Role r : clusterRole.values()) out.stat("clusters." + r);
        LocalDate end = null;
        for (MailDerived m : mails) if (m.date() != null && (end == null || m.date().isAfter(end))) end = m.date();
        for (StatementContent s : statements) if (s.period() != null && (end == null || s.period().atEndOfMonth().isAfter(end))) end = s.period().atEndOfMonth();
        if (end == null) return out;

        // ---- 2. occurrences in factual bank mails
        List<Occ> occs = new ArrayList<>();
        Map<String, SlotStat> slots = new HashMap<>();
        Map<Integer, List<Occ>> byMail = new HashMap<>();
        Map<String, Set<String>> rejectedNumbers = new TreeMap<>();
        Set<String> prepaidNumbers = new HashSet<>();
        Set<String> loanNumbers = new HashSet<>();
        for (int i = 0; i < mails.size(); i++) {
            MailDerived m = mails.get(i);
            Role role = clusterRole.get(m.clusterId());
            // a wallet/prepaid sighting vetoes the number whatever the mail's role (a wallet top-up is often OTHER)
            if (!m.isApp()) for (Slot s : m.slots()) if (s.label() == LabelKind.REJECT_PREPAID)
                prepaidNumbers.add(m.bank() + ":" + s.last4());
            // a loan is out of scope: a number labelled as a loan in a fact-bearing mail is never reported. A mail
            // that gives no facts (an offer of a loan on a card, OTHER/MARKETING/OTP) can not veto a number either.
            if (!m.isApp() && role.factual()) for (Slot s : m.slots()) if (s.label() == LabelKind.LOAN) loanNumbers.add(m.bank() + ":" + s.last4());
            if (!role.factual()) {
                out.drop("mail role " + role + " (no facts)");
                continue;
            }
            if (opt.roles() != null && !opt.roles().contains(role)) continue;
            if (m.isApp()) continue;
            for (Slot s : m.slots()) {
                if (!s.label().rejected() && placeholder(s.last4())) {
                    rejectedNumbers.computeIfAbsent("number rejected: placeholder shape", k -> new HashSet<>()).add(m.bank() + ":" + s.last4());
                    continue;
                }
                if (s.label().rejected()) {
                    rejectedNumbers.computeIfAbsent("number rejected: " + s.label().name().substring(7).toLowerCase(), k -> new HashSet<>())
                            .add(m.bank() + ":" + s.last4());
                    continue;
                }
                Occ o = new Occ(m.bank(), s.last4(), s.slotKey(), s.label(), m.clusterId(), role, m.clusterKind(), m.date(), i);
                occs.add(o);
                byMail.computeIfAbsent(i, k -> new ArrayList<>()).add(o);
                SlotStat st = slots.computeIfAbsent(s.slotKey(), k -> new SlotStat(k, m.clusterId()));
                st.mailsByValue.computeIfAbsent(s.last4(), k -> new HashSet<>()).add(i);
                st.mails.add(i);
            }
        }
        rejectedNumbers.forEach((k, v) -> out.dropped.merge(k, v.size(), Integer::sum));

        // ---- 3. slot classes by cardinality over the whole mailbox
        for (SlotStat st : slots.values()) {
            int n = st.mails.size();
            int d = st.mailsByValue.size();
            // weight by distinct numbers and months, not by mail count: one busy payee must not make a slot look own
            int singles = 0, recurring = 0;
            for (Set<Integer> ms : st.mailsByValue.values()) {
                if (ms.size() == 1) singles++;
                Set<YearMonth> mo = new HashSet<>();
                for (int mi : ms) if (mails.get(mi).date() != null) mo.add(YearMonth.from(mails.get(mi).date()));
                if (mo.size() >= 2) recurring++;
            }
            int listMails = 0;
            for (int mi : st.mails) {
                Set<String> vals = new HashSet<>();
                for (Occ o : byMail.get(mi)) if (o.slotKey().equals(st.key)) vals.add(o.last4());
                if (vals.size() >= 3) listMails++;
            }
            if (listMails * 2 > n) st.cls = SlotClass.MIXED; // a list of numbers in one mail (sample, help text), never one own number
            else if (n < 3) st.cls = SlotClass.UNSCORED;
            else if (d <= 2 || (d <= 6 && recurring >= 0.8 * d)) st.cls = SlotClass.OWN;
            else if (d >= 3 && singles * 2 >= d) st.cls = SlotClass.COUNTERPARTY;
            else st.cls = SlotClass.MIXED;
        }
        // cross-cluster: a value is "seen in" a cluster, on that cluster's channel, only where the sighting would
        // count as evidence (an OWN-candidate slot, or an unscored slot with a kind label)
        Map<String, Map<String, Channel>> valueClusters = new HashMap<>();
        for (Occ o : occs) {
            SlotClass c = slots.get(o.slotKey()).cls;
            if (c == SlotClass.OWN || (c == SlotClass.UNSCORED && o.label() != LabelKind.NONE))
                valueClusters.computeIfAbsent(o.bank() + ":" + o.last4(), k -> new HashMap<>()).put(o.cluster(), Channel.of(o.role()));
        }
        for (StatementContent s : statements) {
            Channel dc = docChannel(s, clusterRole);
            String where = s.fromMail() ? s.originCluster() : "stmt-content";
            if (s.last4() != null) valueClusters.computeIfAbsent(s.bank() + ":" + s.last4(), k -> new HashMap<>()).putIfAbsent(where, dc);
            for (String a : s.addOnLast4()) valueClusters.computeIfAbsent(s.bank() + ":" + a, k -> new HashMap<>()).putIfAbsent(where, dc);
        }
        Map<String, String> bankOfCluster = new HashMap<>();
        for (Occ o : occs) bankOfCluster.put(o.cluster(), o.bank());
        for (SlotStat st : slots.values()) {
            if (st.cls != SlotClass.OWN) continue;
            String bank = bankOfCluster.get(st.cluster);
            Channel mine = Channel.of(clusterRole.get(st.cluster));
            boolean cross = false;
            // the bridge needs the value in another layout on a different channel (two alert layouts are one channel)
            for (String v : st.mailsByValue.keySet())
                for (var cc : valueClusters.getOrDefault(bank + ":" + v, Map.of()).entrySet())
                    if (!cc.getKey().equals(st.cluster) && cc.getValue() != mine) cross = true;
            if (!cross) st.cls = SlotClass.LOCAL;
        }
        // pairing: in a debit/transfer layout, a slot (of any class) that rides along with a stronger own number of the
        // same bank in another slot is the other party (a landlord, family, a payee in two layouts). Strength = the
        // number of mails a value appears in across the mailbox; decided on the classes before this pass.
        Map<String, Set<Integer>> strength = new HashMap<>();
        for (Occ o : occs) strength.computeIfAbsent(o.bank() + ":" + o.last4(), k -> new HashSet<>()).add(o.mail());
        Map<SlotStat, SlotClass> before = new HashMap<>();
        for (SlotStat st : slots.values()) before.put(st, st.cls);
        for (SlotStat st : slots.values()) {
            SlotClass c0 = before.get(st);
            if (c0 == SlotClass.COUNTERPARTY || c0 == SlotClass.MIXED) continue;
            int paired = 0;
            for (int mi : st.mails) {
                MailDerived m = mails.get(mi);
                Role r = clusterRole.get(m.clusterId());
                if (r != Role.TXN_ALERT && r != Role.ACCOUNT_SERVICE) continue;
                Set<String> mine = new HashSet<>();
                int mineStrength = 0;
                for (Occ o : byMail.getOrDefault(mi, List.of())) {
                    if (slots.get(o.slotKey()) != st) continue;
                    mine.add(o.last4());
                    mineStrength = Math.max(mineStrength, strength.get(o.bank() + ":" + o.last4()).size());
                }
                for (Occ o : byMail.getOrDefault(mi, List.of())) {
                    SlotStat other = slots.get(o.slotKey());
                    SlotClass oc = before.get(other);
                    if (other == st || mine.contains(o.last4()) || (oc != SlotClass.OWN && oc != SlotClass.LOCAL)) continue;
                    if (strength.get(o.bank() + ":" + o.last4()).size() > mineStrength) {
                        paired++;
                        break;
                    }
                }
            }
            if (paired > 0 && paired >= 0.8 * st.mails.size()) st.cls = SlotClass.COUNTERPARTY;
        }
        for (SlotStat st : slots.values()) out.stat("slots." + st.cls);

        // ---- 3b. tie each statement document to an entity by its content: bank + header last4 + period; without a
        // last4, through the bank's billing mails dated period end +0..7 days, only when they name exactly one own number
        Set<StatementContent> periodLinked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // period links to a bank with several own numbers of the statement's kind: their facts stay LOW (Contract)
        Set<StatementContent> weakLinked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<StatementContent> resolved = new ArrayList<>();
        // numbers a statement's own header names as the holder's: witness dates per bank:last4. Mail-slot
        // classification (counterparty, mixed, loan, prepaid, seen once) never suppresses these.
        Map<String, TreeSet<LocalDate>> headerDates = new HashMap<>();
        for (StatementContent s : statements) {
            if (s.last4() != null) {
                TreeSet<LocalDate> hd = headerDates.computeIfAbsent(s.bank() + ":" + s.last4(), k -> new TreeSet<>());
                LocalDate wd = witnessDate(s);
                if (wd != null) hd.add(wd);
                out.statementLinks.add(new Detection.StatementLink(s.source(), LINK_LAST4, s.bank(), s.last4(), s.period(), s));
                resolved.add(s);
                continue;
            }
            Set<String> fits = new TreeSet<>();
            String how = LINK_PERIOD;
            if (s.fromMail()) {
                // a PDF from a mail is that mail's evidence: tie it to the one own number its mail names
                how = LINK_ORIGIN;
                for (var bm : byMail.entrySet()) {
                    MailDerived m = mails.get(bm.getKey());
                    if (!s.bank().equals(m.bank()) || !s.originCluster().equals(m.clusterId()) || !java.util.Objects.equals(s.originDate(), m.date())) continue;
                    for (Occ o : bm.getValue()) if (ownish(slots.get(o.slotKey()).cls, o)) fits.add(o.last4());
                }
            } else if (s.periodEnd() != null) {
                for (var bm : byMail.entrySet()) {
                    MailDerived m = mails.get(bm.getKey());
                    Role r = clusterRole.get(m.clusterId());
                    if (!s.bank().equals(m.bank()) || (r != Role.STATEMENT && r != Role.DUE_REMINDER) || m.date() == null) continue;
                    long lag = ChronoUnit.DAYS.between(s.periodEnd(), m.date());
                    if (lag < 0 || lag > 7) continue;
                    for (Occ o : bm.getValue()) if (ownish(slots.get(o.slotKey()).cls, o) && voteKind(o) == s.kind()) fits.add(o.last4());
                }
                // siblings: another own number of the same bank and kind active in that period makes the link ambiguous
                YearMonth pm = YearMonth.from(s.periodEnd());
                Set<String> active = new TreeSet<>();
                for (Occ o : occs) {
                    if (!o.bank().equals(s.bank()) || o.date() == null || !ownish(slots.get(o.slotKey()).cls, o) || voteKind(o) != s.kind()) continue;
                    YearMonth om = YearMonth.from(o.date());
                    if (om.equals(pm) || om.equals(pm.plusMonths(1))) active.add(o.last4());
                }
                if (active.size() > 1) fits.clear();
            }
            if (fits.size() == 1) {
                StatementContent l = s.withLast4(fits.iterator().next());
                out.statementLinks.add(new Detection.StatementLink(s.source(), how, s.bank(), l.last4(), s.period(), s));
                periodLinked.add(l);
                // a statement tied only by its period is a guess: every fact it gives is LOW (contract), however
                // many own numbers of that kind the bank has
                if (how.equals(LINK_PERIOD)) weakLinked.add(l);
                resolved.add(l);
            } else {
                out.statementLinks.add(new Detection.StatementLink(s.source(), LINK_NONE, s.bank(), null, s.period(), s));
            }
        }
        statements = resolved;

        // ---- 4. evidence per (bank, last4)
        Map<String, Ev> ev = new TreeMap<>();
        Map<String, Set<SlotClass>> classesSeen = new HashMap<>();
        for (Occ o : occs) {
            String key = o.bank() + ":" + o.last4();
            SlotClass c = slots.get(o.slotKey()).cls;
            classesSeen.computeIfAbsent(key, k -> EnumSet.noneOf(SlotClass.class)).add(c);
            Ev e = ev.computeIfAbsent(key, k -> new Ev());
            if (c == SlotClass.COUNTERPARTY) {
                e.counterparty++;
                continue;
            }
            if (c == SlotClass.MIXED) continue;
            if (c == SlotClass.UNSCORED && o.label() == LabelKind.NONE) continue;
            Channel ch = Channel.of(o.role());
            if (c == SlotClass.OWN) e.strong++;
            else if (c == SlotClass.LOCAL) e.local++;
            else e.mentions++;
            e.evidence(o.mail(), ch, o.date());
            MailDerived om = mails.get(o.mail());
            if (c == SlotClass.LOCAL) {
                // a debit card is the instrument used even in a transfer alert; a credit card in a transfer mail may
                // be the payee of a bill payment
                if (o.label() == LabelKind.DEBIT_CARD || (o.label() == LabelKind.CREDIT_CARD || o.label() == LabelKind.CARD) && !om.transfer()) e.localCard++;
                else if (o.label() != LabelKind.NONE) e.localOther++;   // an unlabelled sighting says nothing either way
            }
            if (om.balance() || o.role() == Role.STATEMENT) e.balanceSeen = true;
            if (om.transfer()) e.transferMails++;
            else e.plainMails++;
            e.channels.add(ch);
            e.clusters.add(o.cluster());
            e.mailSightings.add(o.cluster() + "|" + o.date());
            e.seen(o.date());
            if (ch == Channel.BILLING && o.date() != null) e.billing.add(YearMonth.from(o.date()));
            if (ch == Channel.ALERTS && o.date() != null) e.alertMonths.add(YearMonth.from(o.date()));
            Kind k = voteKind(o);
            if (k != null) e.vote(k, ch, o.date());
            else if (o.label() == LabelKind.LOAN) e.otherKindVotes++;
        }
        for (StatementContent s : statements) {
            Channel dch = docChannel(s, clusterRole);
            // the witness date: the carrying mail's date for a mail's PDF (two PDFs in one mail are one witness, never
            // a span), else the period end
            LocalDate sd = witnessDate(s);
            // a statement tied only by its period adds no evidence and no kind vote: its number came from the very
            // mails it would confirm
            if (weakLinked.contains(s)) continue;
            if (periodLinked.contains(s) || (s.fromMail() && s.last4() != null)) {
                // tied through a mail (or read from a mail's own PDF): the same evidence as that mail. Its kind vote
                // counts on the mail's channel; it adds no existence channel of its own.
                Ev e = ev.get(s.bank() + ":" + s.last4());
                if (e != null && (s.originCluster() == null || e.mailSightings.contains(s.originCluster() + "|" + s.originDate()))) {
                    e.vote(s.kind(), dch, sd);
                    e.balanceSeen = true;   // a statement document names it
                    e.doc = true;
                } else if (s.fromMail()) {
                    // the mail's text never names the number, only its attached statement does: that statement is
                    // the mail's evidence, on the mail's own channel and layout
                    if (e == null) e = ev.computeIfAbsent(s.bank() + ":" + s.last4(), k -> new Ev());
                    e.strong++;
                    e.channels.add(dch);
                    e.clusters.add(s.originCluster());
                    e.seen(sd);
                    e.evidence(s, dch, sd);
                    if (s.kind() == Kind.CREDIT_CARD && s.period() != null) e.billing.add(s.period());
                    e.vote(s.kind(), dch, sd);
                    e.balanceSeen = true;
                    e.doc = true;
                }
            } else if (s.last4() != null) {
                Ev e = ev.computeIfAbsent(s.bank() + ":" + s.last4(), k -> new Ev());
                e.strong++;
                e.channels.add(dch);
                e.clusters.add("stmt-content");
                e.seen(sd);
                e.evidence(s, dch, sd);
                if (s.kind() == Kind.CREDIT_CARD && s.period() != null) e.billing.add(s.period());
                e.vote(s.kind(), dch, sd);
                e.balanceSeen = true;
                e.doc = true;
            }
            if (s.last4() == null) continue;
            for (String a : s.addOnLast4()) {
                Ev e = ev.computeIfAbsent(s.bank() + ":" + a, k -> new Ev());
                e.strong++;
                e.channels.add(dch);
                e.clusters.add("stmt-content-addon");
                e.seen(sd);
                e.evidence(s, dch, sd);
                e.vote(Kind.CREDIT_CARD, dch, sd);
                e.doc = true;
            }
        }
        // payment apps: issuer + number in one sentence; confirms, never creates
        Map<String, TreeSet<YearMonth>> appMonths = new HashMap<>();
        Map<String, List<AppAt>> appDated = new HashMap<>();
        Map<String, List<AppAt>> appIndependent = new HashMap<>();   // app hits with no bank mail of that number within 2 days
        for (MailDerived m : mails) {
            if (!m.isApp() || !clusterRole.get(m.clusterId()).factual()) continue;
            if (opt.roles() != null && !opt.roles().contains(clusterRole.get(m.clusterId()))) continue;
            for (AppHit h : m.appHits()) {
                String key = h.bank() + ":" + h.last4();
                out.appHits.merge(key, 1, Integer::sum);
                if (m.date() != null) appDated.computeIfAbsent(key, k -> new ArrayList<>()).add(new AppAt(h, m.date()));
                if (m.date() != null) appMonths.computeIfAbsent(key, k -> new TreeSet<>()).add(YearMonth.from(m.date()));
            }
        }

        // a number that is never the only number in any bank mail rides along with another one (a limit, a
        // reference, a second party): it can not be an own account on mail evidence alone
        Map<String, Integer> aloneMails = new HashMap<>();
        for (List<Occ> os : byMail.values()) {
            Set<String> vals = new HashSet<>();
            for (Occ o : os) vals.add(o.last4());
            if (vals.size() == 1) aloneMails.merge(os.get(0).bank() + ":" + os.get(0).last4(), 1, Integer::sum);
        }

        // ---- 5. entities + existence confidence
        for (var en : ev.entrySet()) {
            Ev e = en.getValue();
            String key = en.getKey();
            int bankEvidence = e.strong + e.local + e.mentions;
            boolean header = headerDates.containsKey(key);
            if (e.strong + e.local == 0 && !header) {
                if (e.counterparty > 0) out.drop("number in counterparty slot only");
                else if (e.mentions > 0) out.drop("number in unscored single-layout mention only");
                else if (classesSeen.getOrDefault(key, Set.of()).contains(SlotClass.MIXED)) out.drop("number in mixed slot only");
                continue;
            }
            if (!e.doc && aloneMails.getOrDefault(key, 0) == 0) {
                out.drop("number never alone in a mail (rides with another number)");
                continue;
            }
            if (e.clusters.size() < 2 && e.months.size() < 2 && !header) {
                out.drop("number seen once (one layout, one month)");
                continue;
            }
            // a number ever called a wallet or prepaid instrument (a payments-bank wallet id) is not an own account or card
            if (prepaidNumbers.contains(key) && !header) {
                out.drop("number also named as a wallet or prepaid instrument");
                continue;
            }
            if (loanNumbers.contains(key) && !header) {
                out.drop("number named as a loan (loans are out of scope)");
                continue;
            }
            String bank = key.substring(0, key.indexOf(':'));
            Entity ent = new Entity(bank, key.substring(key.indexOf(':') + 1));
            ent.channels.addAll(e.channels);
            if (appMonths.containsKey(key)) ent.channels.add(Channel.PAYMENT_APP);
            ent.counterpartyHits = e.counterparty;
            // one layout only is LOW (a payee can recur in one layout), unless every sighting names it as the user's own
            // card: a card number in an alert is the instrument used, not the other party
            ent.localOnly = e.strong == 0 && !(e.localCard >= 2 && e.localOther == 0);
            ent.first = e.first;
            ent.last = e.last;
            ent.billingMonths.addAll(e.billing);
            ent.activityMonths.addAll(e.months);
            // payment-app months never feed activity (and so never a lifecycle timeline): apps confirm, never create
            // a folder/DB statement is the billing channel unless alerts or service mails also name the number
            boolean alertsOrService = e.bankChannels.contains(Channel.ALERTS) || e.bankChannels.contains(Channel.SERVICE);
            ent.bankChannels.addAll(docAsBilling(e.bankChannels, alertsOrService));
            ent.transferOnly = !e.balanceSeen && e.plainMails == 0 && e.transferMails > 0;
            ent.evidenceMails = e.evidence.size();
            ent.evidenceSpanDays = span(e.evidenceDates);
            ent.statementOnly = e.bankChannels.equals(EnumSet.of(Channel.STATEMENT_CONTENT));
            if (header) {
                ent.headerStatements = Math.max(1, headerDates.get(key).size());
                ent.headerSpanDays = span(headerDates.get(key));
            }
            // payment apps never count toward existence: only bank-side channels do (Contract)
            ent.existsConf = Contract.existsMax(ent);
            if (e.counterparty > 0) out.ownSeenAsCounterparty.add(key);

            // kind: majority with >= 80% agreement
            int total = e.otherKindVotes;
            Kind top = null;
            int topN = 0;
            for (var kv : e.kindVotes.entrySet()) {
                total += kv.getValue();
                if (kv.getValue() > topN) {
                    top = kv.getKey();
                    topN = kv.getValue();
                }
            }
            // a payment-app mail confirms kind (and variant) only when no bank mail names the number within 2 days:
            // an app message on the same day as the bank's own alert may just repeat it
            List<AppAt> independent = new ArrayList<>();
            for (AppAt a : appDated.getOrDefault(key, List.of())) {
                LocalDate lo = a.date().minusDays(APP_COINCIDENT_DAYS), hi = a.date().plusDays(APP_COINCIDENT_DAYS);
                if (e.evidenceDates.subSet(lo, true, hi, true).isEmpty()) independent.add(a);
            }
            appIndependent.put(key, independent);
            if (top != null && topN >= 0.8 * total && top == Kind.CREDIT_CARD && BankDirectory.isPaymentsBank(bank)) {
                out.drop("kind: a payments bank can not issue a credit card");
            } else if (top != null && topN >= 0.8 * total) {
                ent.kind = top;
                TreeSet<LocalDate> kd = e.kindDates.getOrDefault(top, new TreeSet<>());
                ent.kindUnanimous = topN == total;
                ent.kindChannels = docAsBilling(e.kindChannels.get(top), alertsOrService).size();
                boolean appAgrees = false;
                for (AppAt a : independent) {
                    Kind ak = a.hit().label() == LabelKind.CREDIT_CARD ? Kind.CREDIT_CARD : a.hit().label() == LabelKind.DEBIT_CARD ? Kind.DEBIT_CARD : null;
                    if (ak == null) continue;
                    if (ak == top) appAgrees = true;
                    else ent.kindUnanimous = false;
                }
                // an independent app mail confirms the kind but adds no channel: it can not lift the kind to HIGH
                if (appAgrees) ent.kindAppConfirmed = true;
                ent.kindMails = kd.size();
                ent.kindSpanDays = span(kd);
                ent.kindConf = Conf.HIGH;   // the contract caps it
            } else if (top != null) {
                out.drop("kind: < 80% agreement");
            }
            out.entities.add(ent);
        }

        variants(mails, statements, weakLinked, clusterRole, opt, appIndependent, out);
        lifecycle(mails, statements, weakLinked, clusterRole, opt, out, end);
        limits(mails, statements, weakLinked, clusterRole, opt, out);
        Contract.enforce(out);
        Contract.verify(out);
        for (Entity e : out.entities) {
            if (e.last == null) continue;
            LocalDate last = e.last;
            if (!e.activityMonths.isEmpty() && e.activityMonths.last().atEndOfMonth().isAfter(last)) last = e.activityMonths.last().atDay(1);
            long days = ChronoUnit.DAYS.between(last, end);
            e.status = days <= 60 ? Status.ACTIVE : days <= 180 ? Status.QUIET : Status.DORMANT;
        }
        out.entities.sort((a, b) -> a.key().compareTo(b.key()));
        return out;
    }

    static long span(TreeSet<LocalDate> dates) {
        return dates.size() < 2 ? 0 : ChronoUnit.DAYS.between(dates.first(), dates.last());
    }

    private static boolean ownish(SlotClass c, Occ o) {
        return c == SlotClass.OWN || c == SlotClass.LOCAL || (c == SlotClass.UNSCORED && o.label() != LabelKind.NONE);
    }

    /** STATEMENT_CONTENT counts as BILLING unless the number also has alerts or service evidence. */
    static Set<Channel> docAsBilling(Set<Channel> chs, boolean alertsOrService) {
        Set<Channel> c = EnumSet.noneOf(Channel.class);
        c.addAll(chs);
        if (!alertsOrService && c.remove(Channel.STATEMENT_CONTENT)) c.add(Channel.BILLING);
        return c;
    }

    /** The witness date of a statement: the mail that carried it (one witness with that mail), else its period end. */
    static LocalDate witnessDate(StatementContent s) {
        if (s.fromMail() && s.originDate() != null) return s.originDate();
        return s.periodEnd() != null ? s.periodEnd() : s.period() == null ? null : s.period().atEndOfMonth();
    }

    /**
     * The channel a statement document testifies on: a PDF from a mail is on the channel of the mail carrying it;
     * a folder/DB document (or one carried by a mail with no factual role) is statement content, which counts as
     * billing unless alerts or service mails also name the number ({@link #docAsBilling}).
     */
    static Channel docChannel(StatementContent s, Map<String, Role> clusterRole) {
        if (!s.fromMail()) return Channel.STATEMENT_CONTENT;
        Role r = clusterRole.get(s.originCluster());
        Channel c = r == null ? null : Channel.of(r);
        return c == null ? Channel.STATEMENT_CONTENT : c;
    }

    /**
     * Placeholder shapes used in sample text ("XXXX1234"): one digit repeated, or a straight ascending or descending
     * run. Bank-agnostic; a real number with this shape is lost (blank beats a confident wrong value).
     */
    static boolean placeholder(String last4) {
        if (last4 == null || last4.length() != 4) return false;
        boolean same = true, up = true, down = true;
        for (int i = 1; i < 4; i++) {
            int a = last4.charAt(i - 1), b = last4.charAt(i);
            same &= a == b;
            up &= b == a + 1;
            down &= b == a - 1;
        }
        return same || up || down;
    }

    /** Each layout cluster's role: the majority of its mails' votes (ties go to the more restrictive role). */
    public static Map<String, Role> clusterRoles(List<MailDerived> mails) {
        Map<String, Map<Role, Integer>> votes = new HashMap<>();
        for (MailDerived m : mails) votes.computeIfAbsent(m.clusterId(), k -> new EnumMap<>(Role.class)).merge(m.roleVote(), 1, Integer::sum);
        Map<String, Role> clusterRole = new HashMap<>();
        for (var e : votes.entrySet()) {
            Role best = Role.OTHER;
            int bestN = -1;
            for (var v : e.getValue().entrySet()) {
                if (v.getValue() > bestN || (v.getValue() == bestN && v.getKey().ordinal() > best.ordinal())) {
                    best = v.getKey();
                    bestN = v.getValue();
                }
            }
            clusterRole.put(e.getKey(), best);
        }
        return clusterRole;
    }

    /** Kind vote of one own occurrence: the label's own words first, then the layout's subject words, then the role. */
    private static Kind voteKind(Occ o) {
        switch (o.label()) {
            case CREDIT_CARD: return Kind.CREDIT_CARD;
            case DEBIT_CARD: return Kind.DEBIT_CARD;
            case ACCOUNT: return Kind.ACCOUNT;
            case LOAN: return null;
            default: break;
        }
        if (o.clusterKind() != null) {
            if (o.label() == LabelKind.CARD && o.clusterKind() == Kind.ACCOUNT) return null;
            return o.clusterKind();
        }
        if (o.label() == LabelKind.CARD && (o.role() == Role.STATEMENT || o.role() == Role.DUE_REMINDER)) return Kind.CREDIT_CARD;
        if (o.label() == LabelKind.NONE && o.role() == Role.DUE_REMINDER) return Kind.CREDIT_CARD;
        return null;
    }

    // ------------------------------------------------------------------------------------ variants

    private static void variants(List<MailDerived> mails, List<StatementContent> statements, Set<StatementContent> weak,
                                 Map<String, Role> clusterRole, Options opt, Map<String, List<AppAt>> appIndependent, Detection out) {
        Map<String, Map<String, Set<Channel>>> names = new HashMap<>();
        Set<String> strong = new HashSet<>();   // key|variant with support beyond weak period links
        Map<String, Map<String, TreeSet<LocalDate>>> months = new HashMap<>();
        for (MailDerived m : mails) {
            Role r = clusterRole.get(m.clusterId());
            if (r != Role.STATEMENT && r != Role.CARD_SERVICE) continue;
            if (opt.roles() != null && !opt.roles().contains(r)) continue;
            for (VariantHit h : m.variants()) {
                if (h.last4() == null) {
                    out.drop("variant phrase without a number in the same segment");
                    continue;
                }
                String key = m.bank() + ":" + h.last4();
                String name = ProductCatalog.name(m.bank(), h.name());
                if (name == null) continue;
                names.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(name, k -> EnumSet.noneOf(Channel.class)).add(Channel.of(r));
                strong.add(key + "|" + name);
                if (m.date() != null) months.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(name, k -> new TreeSet<>()).add(m.date());
            }
        }
        for (StatementContent s : statements) {
            // the catalogue name, else the product phrase as printed (kept raw, LOW at most)
            String name = ProductCatalog.name(s.bank(), s.variant() != null ? s.variant() : s.rawProduct());
            if (name == null || s.last4() == null) continue;
            String key = s.bank() + ":" + s.last4();
            Set<Channel> chs = names.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(name, k -> EnumSet.noneOf(Channel.class));
            // a weakly (period-) linked statement names the product but adds no channel, no witness and no span
            if (weak.contains(s)) continue;
            chs.add(docChannel(s, clusterRole));
            strong.add(key + "|" + name);
            LocalDate sd = witnessDate(s);
            if (sd != null) months.computeIfAbsent(key, k -> new TreeMap<>()).computeIfAbsent(name, k -> new TreeSet<>()).add(sd);
        }
        for (Entity e : out.entities) {
            Map<String, Set<Channel>> n = names.get(e.key());
            if (n == null) continue;
            Map<String, TreeSet<LocalDate>> seenOn = months.getOrDefault(e.key(), Map.of());
            String v;
            boolean nestedUnsettled = false;
            if (n.size() == 2 && ProductCatalog.nested(e.bank, n.keySet().stream().toList().get(0), n.keySet().stream().toList().get(1))) {
                // one number under a name and a longer name containing it ("Regalia", "Regalia Gold"): the timeline
                // settles it only when every longer-name sighting comes after every shorter one (an upgrade kept the
                // number); otherwise the latest name is kept LOW with both as candidates
                List<String> two = new ArrayList<>(n.keySet());
                two.sort((a, b) -> ProductCatalog.longerNames(e.bank, a).contains(b) ? -1 : 1);
                String shorter = two.get(0), longer = two.get(1);
                TreeSet<LocalDate> ds = seenOn.getOrDefault(shorter, new TreeSet<>()), dl = seenOn.getOrDefault(longer, new TreeSet<>());
                if (!ds.isEmpty() && !dl.isEmpty() && dl.first().isAfter(ds.last())) {
                    v = longer;
                } else {
                    nestedUnsettled = true;
                    LocalDate ls = ds.isEmpty() ? null : ds.last(), ll = dl.isEmpty() ? null : dl.last();
                    v = ll != null && (ls == null || !ls.isAfter(ll)) ? longer : shorter;
                    e.variantCandidates.addAll(two);
                    out.drop("variant: nested names on one number, not settled by the timeline");
                }
            } else if (n.size() > 1) {
                e.variantConflicts = n.size();
                out.drop("variant: conflicting product names");
                continue;
            } else {
                v = n.keySet().iterator().next();
            }
            if (e.kind != Kind.CREDIT_CARD) {
                out.drop("variant: entity is not a credit card");
                continue;
            }
            TreeSet<LocalDate> ms = seenOn.getOrDefault(v, new TreeSet<>());
            e.variant = v;
            e.variantChannels = docAsBilling(n.get(v), e.bankChannels.contains(Channel.ALERTS) || e.bankChannels.contains(Channel.SERVICE)).size();
            e.variantWeakLink = !strong.contains(e.key() + "|" + v);
            // a name the catalogue does not know, or a tier word alone ("Platinum"), never identifies a product above
            // LOW, unless this issuer sells that tier word as a product of its own (Amex Gold)
            e.variantAmbiguous = !ProductCatalog.isCatalogued(e.bank, v) || ProductCatalog.tierOnly(v) && !ProductCatalog.tierProduct(e.bank, v);
            // a name that nests with another catalogued product of the issuer, either way ("Regalia" / "Regalia Gold",
            // "Zenith" / "Zenith+"), is never above LOW, even when the longer name was the one seen or an upgrade
            // timeline settled it: the names seen and the products it nests with are the candidates
            List<String> nestedWith = ProductCatalog.nestedNames(e.bank, v);
            if (nestedUnsettled || !nestedWith.isEmpty()) {
                e.variantAmbiguous = true;
                java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>(e.variantCandidates);
                cands.add(v);
                for (String o : n.keySet()) if (o.equals(v) || nestedWith.contains(o)) cands.add(o);
                cands.addAll(nestedWith);
                e.variantCandidates.clear();
                e.variantCandidates.addAll(cands);
                if (!nestedUnsettled) out.drop("variant: the name nests with another product of the issuer");
            }
            // a payment app (not coincident with a bank mail) naming the same product is one more channel; naming
            // another product keeps the name LOW
            boolean appSame = false, appOther = false;
            for (AppAt a : appIndependent.getOrDefault(e.key(), List.of())) {
                if (a.hit().variant() == null) continue;
                if (a.hit().variant().equals(v)) appSame = true;
                else appOther = true;
            }
            if (appOther) {
                e.variantAmbiguous = true;
                out.drop("variant: a payment app names another product");
            } else if (appSame) {
                e.variantAppConfirmed = true;   // confirms only: no channel, so never the reason for HIGH
            }
            e.variantMails = ms.size();
            e.variantSpanDays = span(ms);
            e.variantConf = Conf.HIGH;   // the contract caps it
        }
    }

    // ------------------------------------------------------------------------------------ lifecycle

    private record EventAt(EventType type, String bank, List<String> last4s, LocalDate date) {}

    private static void lifecycle(List<MailDerived> mails, List<StatementContent> statements, Set<StatementContent> weak,
                                  Map<String, Role> clusterRole, Options opt, Detection out, LocalDate end) {
        YearMonth endYm = YearMonth.from(end);
        List<EventAt> events = new ArrayList<>();
        for (MailDerived m : mails) {
            Role r = clusterRole.get(m.clusterId());
            boolean service = r == Role.CARD_SERVICE || r == Role.ACCOUNT_SERVICE;
            if (!service && r != Role.TXN_ALERT) continue;
            if (opt.roles() != null && !opt.roles().contains(r)) continue;
            if (m.isApp()) continue;
            for (EventHit h : m.events()) {
                // alerts may say "add-on card ending X"; every other lifecycle statement must come from a service mail
                if (h.last4s().isEmpty() || (!service && h.type() != EventType.ADD_ON)) continue;
                events.add(new EventAt(h.type(), m.bank(), h.last4s(), m.date()));
            }
        }
        Map<String, Entity> byKey = new HashMap<>();
        for (Entity e : out.entities) byKey.put(e.key(), e);

        // add-on (statement listing, explicit wording, then timeline)
        Map<String, String> addOnListed = new HashMap<>();
        Map<String, TreeSet<LocalDate>> listedDates = new HashMap<>();   // each listing statement (not weakly linked) is a witness
        for (StatementContent s : statements) for (String a : s.addOnLast4()) if (s.last4() != null && !weak.contains(s)) {
            // a weakly (period-) linked statement's primary is a guess: its listing settles nothing
            addOnListed.put(s.bank() + ":" + a, s.bank() + ":" + s.last4());
            LocalDate wd = witnessDate(s);
            if (!weak.contains(s) && wd != null) listedDates.computeIfAbsent(s.bank() + ":" + a, k -> new TreeSet<>()).add(wd);
        }
        for (Entity x : out.entities) {
            if (!x.billingMonths.isEmpty() && !addOnListed.containsKey(x.key())) continue;
            String primary = addOnListed.get(x.key());
            boolean listed = primary != null && byKey.containsKey(primary) && !primary.equals(x.key());
            TreeSet<LocalDate> witnesses = new TreeSet<>();
            if (listed) witnesses.addAll(listedDates.getOrDefault(x.key(), new TreeSet<>()));
            boolean explicit = false;
            for (EventAt ev : events) {
                if (ev.type() != EventType.ADD_ON || !ev.bank().equals(x.bank) || !ev.last4s().contains(x.last4)) continue;
                explicit = true;
                if (ev.date() != null) witnesses.add(ev.date());
                if (primary == null) {
                    for (String o : ev.last4s()) {
                        Entity p = byKey.get(x.bank + ":" + o);
                        if (p != null && p != x && !p.billingMonths.isEmpty()) primary = p.key();
                    }
                }
            }
            if (primary == null) {
                if (x.kind != Kind.CREDIT_CARD || x.activityMonths.size() < 2) continue;
                if (!x.billingMonths.isEmpty()) continue;
                YearMonth f = x.activityMonths.first();
                if (f.isAfter(endYm.minusMonths(2))) continue;
                List<Entity> cover = new ArrayList<>();
                for (Entity p : out.entities) {
                    if (p == x || !p.bank.equals(x.bank) || p.kind != Kind.CREDIT_CARD || p.billingMonths.size() < 2) continue;
                    if (covers(p, x)) cover.add(p);
                }
                if (cover.size() != 1) {
                    if (cover.size() > 1) out.drop("add-on: more than one possible primary");
                    continue;
                }
                primary = cover.get(0).key();
            }
            Entity p = byKey.get(primary);
            if (p == null || p == x) continue;
            x.lifecycle = Lifecycle.ADD_ON;
            x.linkedTo = primary;
            x.lifecycleWitnesses.clear();
            x.lifecycleWitnesses.addAll(witnesses);
            x.lifecycleTimeline = x.billingMonths.isEmpty() && x.activityMonths.size() >= 2 && covers(p, x);
            x.lifecycleWeakLink = listed && !explicit && witnesses.isEmpty();   // listed only by weakly linked statements
            x.lifecycleConf = Conf.HIGH;   // the contract caps it
        }

        // closure (explicit only), checked against later activity; a block is a LOW status unless a new number follows
        Map<String, LocalDate> closedOn = new HashMap<>(), blockedOn = new HashMap<>();
        Map<String, TreeSet<LocalDate>> closedWitness = new HashMap<>();
        Set<String> closedStrong = new HashSet<>();   // closure with support beyond weakly linked statements
        for (EventAt ev : events) {
            if ((ev.type() != EventType.CLOSED && ev.type() != EventType.BLOCKED) || ev.date() == null) continue;
            for (String l4 : ev.last4s()) {
                String k = ev.bank() + ":" + l4;
                if (ev.type() == EventType.BLOCKED) {
                    blockedOn.merge(k, ev.date(), (a, b) -> a.isAfter(b) ? a : b);
                    continue;
                }
                closedOn.merge(k, ev.date(), (a, b) -> a.isAfter(b) ? a : b);
                closedWitness.computeIfAbsent(k, x -> new TreeSet<>()).add(ev.date());
                closedStrong.add(k);
            }
        }
        for (StatementContent s : statements) {
            if (s.mentionsClosure() && s.last4() != null && s.period() != null) {
                String k = s.bank() + ":" + s.last4();
                closedOn.merge(k, s.period().atEndOfMonth(), (a, b) -> a.isAfter(b) ? a : b);
                if (weak.contains(s)) continue;
                LocalDate wd = witnessDate(s);
                if (wd != null) closedWitness.computeIfAbsent(k, x -> new TreeSet<>()).add(wd);
                closedStrong.add(k);
            }
        }

        // replacement / upgrade
        Map<String, List<Entity>> succ = new HashMap<>(), pred = new HashMap<>();
        for (Entity a : out.entities) {
            if (a.kind != Kind.CREDIT_CARD || a.billingMonths.size() < 2 || a.lifecycle == Lifecycle.ADD_ON) continue;
            if (closedOn.containsKey(a.key())) continue;
            YearMonth aLast = a.billingMonths.last();
            if (aLast.isAfter(endYm.minusMonths(2))) continue;
            for (Entity b : out.entities) {
                if (b == a || !b.bank.equals(a.bank) || b.kind != Kind.CREDIT_CARD || b.billingMonths.size() < 2 || b.lifecycle == Lifecycle.ADD_ON) continue;
                YearMonth bFirst = b.billingMonths.first();
                if (!bFirst.isAfter(a.billingMonths.first()) || !b.billingMonths.last().isAfter(aLast)) continue;
                long gap = ChronoUnit.MONTHS.between(aLast, bFirst);
                if (gap < -1 || gap > 1) continue;
                if (!a.activityMonths.isEmpty() && a.activityMonths.last().isAfter(bFirst.plusMonths(1))) continue;
                succ.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(b);
                pred.computeIfAbsent(b.key(), k -> new ArrayList<>()).add(a);
            }
        }
        Set<String> paired = new HashSet<>();
        for (Entity a : out.entities) {
            List<Entity> s = succ.get(a.key());
            if (s == null) continue;
            if (s.size() != 1 || pred.get(s.get(0).key()).size() != 1) {
                out.drop("replacement: ambiguous successor");
                continue;
            }
            Entity b = s.get(0);
            boolean explicitUpgrade = false;
            TreeSet<LocalDate> witnesses = new TreeSet<>();
            for (EventAt ev : events) {
                if (!ev.bank().equals(a.bank)) continue;
                if (!ev.last4s().contains(a.last4) && !ev.last4s().contains(b.last4)) continue;
                if (ev.type() == EventType.UPGRADED) explicitUpgrade = true;
                if ((ev.type() == EventType.REPLACED || ev.type() == EventType.UPGRADED) && ev.date() != null) witnesses.add(ev.date());
            }
            // the billing hand-over is the corroborating timeline; each explicit mail (distinct date) is a witness
            setPair(a, b, explicitUpgrade, paired, witnesses, true);
        }
        // explicit two-number replacement for any kind, with an activity hand-over
        for (EventAt ev : events) {
            if ((ev.type() != EventType.REPLACED && ev.type() != EventType.UPGRADED) || ev.last4s().size() != 2) continue;
            Entity x = byKey.get(ev.bank() + ":" + ev.last4s().get(0)), y = byKey.get(ev.bank() + ":" + ev.last4s().get(1));
            if (x == null || y == null || x.kind == null || x.kind != y.kind || paired.contains(x.key()) || paired.contains(y.key())) continue;
            // undated sightings only: no timeline to order the two numbers by
            if (x.activityMonths.isEmpty() || y.activityMonths.isEmpty()) continue;
            Entity a = x.activityMonths.last().isBefore(y.activityMonths.last()) ? x : y;
            Entity b = a == x ? y : x;
            if (!b.activityMonths.first().isAfter(a.activityMonths.first())) continue;
            if (a.activityMonths.last().isAfter(b.activityMonths.first().plusMonths(2))) continue;
            TreeSet<LocalDate> witnesses = new TreeSet<>();
            for (EventAt e2 : events) {
                if ((e2.type() == EventType.REPLACED || e2.type() == EventType.UPGRADED) && e2.bank().equals(ev.bank()) && e2.date() != null
                        && (e2.last4s().contains(a.last4) || e2.last4s().contains(b.last4))) witnesses.add(e2.date());
            }
            // timeline: the old number's activity stops and the new one's starts within 2 months of the first mail
            boolean timeline = false;
            if (!witnesses.isEmpty()) {
                YearMonth em = YearMonth.from(witnesses.first());
                timeline = within(a.activityMonths.last(), em, 2) && within(b.activityMonths.first(), em, 2);
            }
            setPair(a, b, ev.type() == EventType.UPGRADED, paired, witnesses, timeline);
        }

        for (var c : closedOn.entrySet()) {
            Entity e = byKey.get(c.getKey());
            if (e == null) continue;
            if (e.lifecycle == Lifecycle.REPLACED || e.lifecycle == Lifecycle.UPGRADED || e.lifecycle == Lifecycle.ADD_ON
                    || e.lifecycle == Lifecycle.REPLACEMENT) continue;
            YearMonth cm = YearMonth.from(c.getValue());
            if (!e.activityMonths.isEmpty() && e.activityMonths.last().isAfter(cm.plusMonths(1))) {
                out.drop("closure contradicted by later activity");
                continue;
            }
            e.lifecycle = Lifecycle.CLOSED;
            e.linkedTo = null;
            e.lifecycleWitnesses.clear();
            e.lifecycleWitnesses.addAll(closedWitness.getOrDefault(c.getKey(), new TreeSet<>()));
            // timeline: active within the 3 months before the closure, silent after it
            e.lifecycleTimeline = !e.activityMonths.subSet(cm.minusMonths(3), true, cm, true).isEmpty();
            e.lifecycleWeakLink = !closedStrong.contains(c.getKey());
            e.lifecycleConf = Conf.HIGH;   // the contract caps it: one explicit mail alone is LOW
        }
        for (var c : blockedOn.entrySet()) {
            Entity e = byKey.get(c.getKey());
            if (e == null || e.lifecycle != null) continue;
            if (!e.activityMonths.isEmpty() && e.activityMonths.last().isAfter(YearMonth.from(c.getValue()).plusMonths(1))) continue;
            e.lifecycle = Lifecycle.CLOSED;
            e.lifecycleFromBlock = true;
            e.lifecycleConf = Conf.LOW;
        }

        // silent stop (LOW only)
        for (Entity e : out.entities) {
            if (e.lifecycle != null || e.kind != Kind.CREDIT_CARD || e.billingMonths.size() < 2) continue;
            if (e.billingMonths.last().isAfter(endYm.minusMonths(3)) || (!e.activityMonths.isEmpty() && e.activityMonths.last().isAfter(endYm.minusMonths(3)))) continue;
            e.lifecycle = Lifecycle.STOPPED;
            e.lifecycleConf = Conf.LOW;
        }
    }

    /** The primary's billing covers the add-on's activity and the add-on has no billing of its own. */
    private static boolean covers(Entity p, Entity x) {
        if (p.billingMonths.isEmpty() || x.activityMonths.isEmpty() || !x.billingMonths.isEmpty()) return false;
        return !p.billingMonths.first().isAfter(x.activityMonths.first()) && !p.billingMonths.last().isBefore(x.activityMonths.last().minusMonths(1));
    }

    private static boolean within(YearMonth a, YearMonth b, int months) {
        return Math.abs(ChronoUnit.MONTHS.between(a, b)) <= months;
    }

    private static void setPair(Entity a, Entity b, boolean explicitUpgrade, Set<String> paired, TreeSet<LocalDate> witnesses, boolean timeline) {
        for (Entity e : List.of(a, b)) {
            e.lifecycleWitnesses.clear();
            e.lifecycleWitnesses.addAll(witnesses);
            e.lifecycleTimeline = timeline;
        }
        boolean variantChange = a.variant != null && b.variant != null && !a.variant.equals(b.variant);
        Lifecycle l = Lifecycle.REPLACED;
        if (explicitUpgrade) {
            l = Lifecycle.UPGRADED;
        } else if (variantChange) {
            l = Lifecycle.UPGRADED;   // the contract caps it by both product names' confidence
            a.lifecycleFromVariants = true;
            b.lifecycleFromVariants = true;
        }
        a.lifecycle = l;
        a.linkedTo = b.key();
        a.lifecycleConf = Conf.HIGH;   // the contract caps it
        b.lifecycle = Lifecycle.REPLACEMENT;
        b.linkedTo = a.key();
        b.lifecycleConf = Conf.HIGH;
        paired.add(a.key());
        paired.add(b.key());
    }

    private static void limits(List<MailDerived> mails, List<StatementContent> statements, Set<StatementContent> weak,
                               Map<String, Role> clusterRole, Options opt, Detection out) {
        Map<String, TreeMap<LocalDate, String>> byKey = new HashMap<>();
        Map<String, TreeMap<LocalDate, String>> weakByKey = new HashMap<>();
        for (StatementContent s : statements) {
            LocalDate wd = witnessDate(s);
            if (s.last4() == null || s.limitBucket() == null || wd == null) continue;
            String k = s.bank() + ":" + s.last4();
            // weakly linked statements never count toward the documents or span behind a limit
            (weak.contains(s) ? weakByKey : byKey).computeIfAbsent(k, x -> new TreeMap<>()).put(wd, s.limitBucket());
        }
        // limit-change mails: the newest change, used only where no statement is newer (LOW unless a statement agrees)
        Map<String, TreeMap<LocalDate, String>> mailLimits = new HashMap<>();
        for (MailDerived m : mails) {
            Role r = clusterRole.get(m.clusterId());
            if (m.isApp() || m.date() == null || m.limits().isEmpty() || (r != Role.CARD_SERVICE && r != Role.ACCOUNT_SERVICE)) continue;
            if (opt.roles() != null && !opt.roles().contains(r)) continue;
            for (MailDerived.LimitHit h : m.limits()) mailLimits.computeIfAbsent(m.bank() + ":" + h.last4(), k -> new TreeMap<>()).put(m.date(), h.bucket());
        }
        for (Entity e : out.entities) {
            TreeMap<LocalDate, String> m = byKey.get(e.key());
            boolean weakOnly = false;
            if (m == null && weakByKey.containsKey(e.key())) {
                m = weakByKey.get(e.key());
                weakOnly = true;
            }
            TreeMap<LocalDate, String> ml = mailLimits.get(e.key());
            if (e.kind != Kind.CREDIT_CARD && (m != null || ml != null)) {
                out.drop("limit: entity is not a credit card");
                continue;
            }
            if (ml != null && (m == null || ml.lastKey().isAfter(m.lastKey()))) {
                String mailBucket = ml.lastEntry().getValue();
                if (m != null && m.lastEntry().getValue().equals(mailBucket)) {
                    // the statement already shows the new limit: the statement path below decides
                } else {
                    e.limitBucket = mailBucket;
                    e.limitFromMail = true;
                    e.limitDocs = 1;
                    e.limitAgree = true;
                    e.limitConf = Conf.LOW;
                    continue;
                }
            }
            if (m == null) continue;
            String latest = m.lastEntry().getValue();
            e.limitBucket = latest;
            e.limitDocs = weakOnly ? 0 : m.size();
            e.limitSpanDays = weakOnly ? 0 : ChronoUnit.DAYS.between(m.firstKey(), m.lastKey());
            e.limitAgree = m.values().stream().allMatch(latest::equals);
            e.limitWeakLink = weakOnly;
            e.limitConf = Conf.MEDIUM;   // the contract caps it
        }
    }

    /** A payment-app hit with its mail date. */
    private record AppAt(AppHit hit, LocalDate date) {}

    /** An app mail within this many days of a bank mail naming the same number is not independent of it. */
    static final int APP_COINCIDENT_DAYS = 2;
}
