package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.MailDerived.AppHit;
import com.financeos.gmail.accountdetect.MailDerived.EventHit;
import com.financeos.gmail.accountdetect.MailDerived.LimitHit;
import com.financeos.gmail.accountdetect.MailDerived.Slot;
import com.financeos.gmail.accountdetect.MailDerived.VariantHit;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * JSON of one mail's stored facts: exactly what {@link com.financeos.gmail.accountdetect.FeatureCache} keeps per mail
 * (bank, hashed cluster and slot keys, role vote, date, cluster kind, own-candidate slots, dictionary variants,
 * events, app hits, layout flags, limit buckets) plus the statement-content rows read from its open PDFs, whether
 * its body was read, its header-only role vote, and how many runs in a row a read of it failed. Like the cache,
 * rejected number shapes other than prepaid never get here. A mail whose headers could not be read has no features.
 */
public final class FactsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final int VERSION = 1;
    /** After this many runs in a row with a failed read, a mail is kept as it is (flagged) and no longer re-read. */
    public static final int GIVE_UP_AFTER_RUNS = 3;

    private FactsCodec() {}

    /**
     * Decoded facts of one mail.
     *
     * @param derived         null when the mail's headers could not be read
     * @param headerVote      the header-only role vote (what the scan's cluster vote counts)
     * @param incompleteRuns  0 = complete; 1..{@link #GIVE_UP_AFTER_RUNS}-1 = partial, re-read next run;
     *                        {@link #GIVE_UP_AFTER_RUNS} = partial for good (flagged, not re-read)
     */
    public record Facts(MailDerived derived, List<StatementContent> statements, boolean bodyFetched, Role headerVote, int incompleteRuns, int readerVersion) {
        public Facts(MailDerived derived, List<StatementContent> statements, boolean bodyFetched, Role headerVote, int incompleteRuns) {
            this(derived, statements, bodyFetched, headerVote, incompleteRuns, StatementPdfReader.READER_VERSION);
        }

        public Facts(MailDerived derived, List<StatementContent> statements, boolean bodyFetched) {
            this(derived, statements, bodyFetched, derived == null ? null : derived.roleVote(), 0);
        }

        /** Its statement PDF was read by an older reader than the current one (a version absent in storage counts as 0). */
        public boolean readerStale() {
            return readerVersion < StatementPdfReader.READER_VERSION;
        }

        /** A read failed and the next run reads the mail again. */
        public boolean pending() {
            return incompleteRuns > 0 && incompleteRuns < GIVE_UP_AFTER_RUNS;
        }

        /** Reads kept failing: stored as it is and no longer re-read. */
        public boolean givenUp() {
            return incompleteRuns >= GIVE_UP_AFTER_RUNS;
        }
    }

    record J(int v, boolean body, M mail, List<S> statements, String hv, Integer inc, Integer rv) {}

    record M(String bank, String cluster, String role, String date, String clusterKind, List<SlotJ> slots, List<VarJ> variants,
             List<EvJ> events, List<AppJ> apps, int layout, List<LimJ> limits) {}

    record SlotJ(String key, String last4, String label) {}

    record VarJ(String name, String last4) {}

    record EvJ(String type, List<String> last4s) {}

    record AppJ(String bank, String last4, String label, String variant) {}

    record LimJ(String last4, String bucket) {}

    record S(String source, String bank, String last4, String kind, String period, String variant, String limitBucket,
             List<String> addOns, boolean closure, String periodEnd, String originCluster, String originDate,
             java.math.BigDecimal limitAmount, String unlockRule, String holder) {}

    public static String encode(MailDerived m, List<StatementContent> statements, boolean bodyFetched) {
        return encode(m, statements, bodyFetched, null, 0);
    }

    public static String encode(MailDerived m, List<StatementContent> statements, boolean bodyFetched, Role headerVote, int incompleteRuns) {
        return encode(m, statements, bodyFetched, headerVote, incompleteRuns, StatementPdfReader.READER_VERSION);
    }

    /** Same, recording the version of the PDF reader that read the statements (see {@link StatementPdfReader#READER_VERSION}). */
    public static String encode(MailDerived m, List<StatementContent> statements, boolean bodyFetched, Role headerVote, int incompleteRuns, int readerVersion) {
        M mail = m == null ? null : new M(m.bank(), m.clusterId(), m.roleVote().name(), str(m.date()), m.clusterKind() == null ? null : m.clusterKind().name(),
                m.slots().stream().filter(s -> !s.label().rejected() || s.label() == LabelKind.REJECT_PREPAID)
                        .map(s -> new SlotJ(s.slotKey(), s.last4(), s.label().name())).toList(),
                m.variants().stream().map(v -> new VarJ(v.name(), v.last4())).toList(),
                m.events().stream().map(e -> new EvJ(e.type().name(), e.last4s())).toList(),
                m.appHits().stream().map(a -> new AppJ(a.bank(), a.last4(), a.label().name(), a.variant())).toList(),
                m.layout(),
                m.limits().stream().map(l -> new LimJ(l.last4(), l.bucket())).toList());
        List<S> ss = statements == null ? List.of() : statements.stream().map(s -> new S(s.source(), s.bank(), s.last4(),
                s.kind() == null ? null : s.kind().name(), s.period() == null ? null : s.period().toString(), s.variant(), s.limitBucket(),
                s.addOnLast4(), s.mentionsClosure(), str(s.periodEnd()), s.originCluster(), str(s.originDate()), s.limitAmount(), s.unlockRule(), s.holder())).toList();
        try {
            String hv = headerVote == null || (m != null && headerVote == m.roleVote()) ? null : headerVote.name();
            return MAPPER.writeValueAsString(new J(VERSION, bodyFetched, mail, ss, hv, incompleteRuns > 0 ? incompleteRuns : null, readerVersion));
        } catch (Exception e) {
            throw new IllegalStateException("could not encode account-detect facts", e);
        }
    }

    public static Facts decode(String json) {
        J j;
        try {
            j = MAPPER.readValue(json, J.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not decode account-detect facts", e);
        }
        M m = j.mail();
        MailDerived d = m == null ? null : new MailDerived(m.bank(), m.cluster(), Role.valueOf(m.role()), date(m.date()),
                m.clusterKind() == null ? null : Kind.valueOf(m.clusterKind()),
                list(m.slots()).stream().map(s -> new Slot(s.key(), s.last4(), LabelKind.valueOf(s.label()))).toList(),
                list(m.variants()).stream().map(v -> new VariantHit(v.name(), v.last4())).toList(),
                list(m.events()).stream().map(e -> new EventHit(EventType.valueOf(e.type()), list(e.last4s()))).toList(),
                list(m.apps()).stream().map(a -> new AppHit(a.bank(), a.last4(), LabelKind.valueOf(a.label()), a.variant())).toList(),
                m.layout(),
                list(m.limits()).stream().map(l -> new LimitHit(l.last4(), l.bucket())).toList());
        List<StatementContent> ss = list(j.statements()).stream().map(s -> new StatementContent(s.source(), s.bank(), s.last4(),
                s.kind() == null ? null : Kind.valueOf(s.kind()), s.period() == null ? null : YearMonth.parse(s.period()), s.variant(),
                s.limitBucket(), list(s.addOns()), s.closure(), date(s.periodEnd()), s.originCluster(), date(s.originDate()), null,
                s.limitAmount(), s.unlockRule(), s.holder())).toList();
        Role hv = j.hv() != null ? Role.valueOf(j.hv()) : d == null ? null : d.roleVote();
        return new Facts(d, ss, j.body(), hv, j.inc() == null ? 0 : j.inc(), j.rv() == null ? 0 : j.rv());
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate date(String s) {
        return s == null || s.isEmpty() ? null : LocalDate.parse(s);
    }
}
