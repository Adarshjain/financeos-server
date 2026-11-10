package com.financeos.gmail.accountdetect.job;

import com.financeos.core.time.AppTime;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Detector;
import com.financeos.gmail.accountdetect.Vocab.Channel;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Numbers the Gmail transaction-alert ingest read fully but could not place on an account (UNRESOLVED_ACCOUNT, ALERT),
 * fed to account detection as one more evidence source: body sampling may never read the first alert of a brand-new card
 * in a busy layout, but the ingest pipeline did. Such a number is only ever a possible one (existence LOW, so band MAYBE),
 * the same tier as the single-alert-at-a-known-bank rule. Matching to existing accounts stays with the sink.
 */
public final class IngestAlertNumbers {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private IngestAlertNumbers() {}

    /** One unresolved ingest row; bank is the detection bank id (lower-case), last4 the number the pipeline read. */
    public record Row(String messageId, String bank, String last4, Instant internalDate, String template) {}

    /**
     * Unresolved, numbered, live alert rows of one user. Archived rows (which includes every ignored number, see
     * IgnoredNumberRules.archiveIfIgnored) and dismissed rows are left out; a number in gmail_ignored_numbers is left
     * out too in case a rule was added after the row.
     */
    public static List<Row> load(JdbcTemplate jdbc, UUID userId) {
        List<Row> rows = new ArrayList<>();
        jdbc.query("SELECT m.gmail_message_id, m.classified_bank, COALESCE(m.extract_last4, m.extracted_last4), m.internal_date, m.extract_template "
                        + "FROM gmail_processed_messages m "
                        + "WHERE m.user_id = ? AND m.status = 'UNRESOLVED_ACCOUNT' AND m.pipeline_kind = 'ALERT' "
                        + "AND COALESCE(m.extract_last4, m.extracted_last4) IS NOT NULL AND m.classified_bank IS NOT NULL "
                        + "AND m.archived_at IS NULL AND m.dismissed_at IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM gmail_ignored_numbers i WHERE i.user_id = m.user_id "
                        + "AND i.bank_id = m.classified_bank AND i.last4 = COALESCE(m.extract_last4, m.extracted_last4))",
                rs -> {
                    String bank = rs.getString(2) == null ? "" : rs.getString(2).trim().toLowerCase(Locale.ROOT);
                    String last4 = rs.getString(3) == null ? "" : rs.getString(3).trim();
                    if (bank.isEmpty() || last4.length() != 4) return;
                    Timestamp ts = rs.getTimestamp(4);
                    rows.add(new Row(rs.getString(1), bank, last4, ts == null ? null : ts.toInstant(), rs.getString(5)));
                }, userId.toString());
        return rows;
    }

    /**
     * The sorted distinct message ids with their bank:last4, for the detection reuse key: any new unresolved alert
     * (a second month of a known number included) re-evaluates, and so does a changed row set.
     */
    public static String fingerprint(List<Row> rows) {
        TreeSet<String> keys = new TreeSet<>();
        for (Row r : rows) keys.add(r.messageId() + "=" + r.bank() + ":" + r.last4());
        return Integer.toHexString(keys.toString().hashCode());
    }

    /**
     * Adds an entity per (bank, last4) of the rows that detection has not already reported. The detection is changed
     * in place and returned.
     *
     * @param kindByMessage cluster kind of the stored account-detect fact of each message id (absent when unknown)
     */
    public static Detection merge(Detection detection, List<Row> rows, Map<String, Kind> kindByMessage, Detector.Options opt) {
        if (rows == null || rows.isEmpty()) return detection;
        Map<String, List<Row>> byNumber = new LinkedHashMap<>();
        for (Row r : rows) byNumber.computeIfAbsent(r.bank() + ":" + r.last4(), k -> new ArrayList<>()).add(r);
        boolean added = false;
        for (Map.Entry<String, List<Row>> en : byNumber.entrySet()) {
            List<Row> rs = en.getValue();
            String bank = rs.get(0).bank(), last4 = rs.get(0).last4();
            if (opt.ownNumbers().contains(last4) || Detector.placeholder(last4)) continue;
            if (detection.find(bank, last4) != null) continue;
            if (solidElsewhere(detection, bank, last4)) continue;
            TreeSet<YearMonth> months = new TreeSet<>();
            TreeSet<LocalDate> dates = new TreeSet<>();
            TreeSet<String> mails = new TreeSet<>();
            for (Row r : rs) {
                mails.add(r.messageId());
                if (r.internalDate() != null) {
                    LocalDate d = r.internalDate().atZone(IST).toLocalDate();
                    dates.add(d);
                    months.add(YearMonth.from(d));
                }
            }
            if (!opt.openBanks().contains(bank) && months.size() < 2) continue;
            Kind kind = kindOf(rs, kindByMessage);
            if (kind == null) continue;
            Detection.Entity e = Detection.Entity.of(bank, last4);
            e.existsConf = Conf.LOW;   // possible only: the band is MAYBE (SuggestionPlanner.band), like singleAlertAtKnownBank
            e.kind = kind;
            e.kindConf = Conf.LOW;
            e.localOnly = true;
            e.channels.add(Channel.ALERTS);
            e.bankChannels.add(Channel.ALERTS);
            e.evidenceMails = mails.size();
            if (!dates.isEmpty()) {
                e.first = dates.first();
                e.last = dates.last();
                e.evidenceSpanDays = ChronoUnit.DAYS.between(e.first, e.last);
                e.activityMonths.addAll(months);
                long days = ChronoUnit.DAYS.between(e.last, AppTime.today());
                e.status = days <= 60 ? Status.ACTIVE : days <= 180 ? Status.QUIET : Status.DORMANT;
            }
            detection.entities.add(e);
            added = true;
        }
        if (added) detection.entities.sort((a, b) -> a.key().compareTo(b.key()));
        return detection;
    }

    /**
     * The same last4 is a solid own number at another bank (existence HIGH, or named by a statement header or
     * statement/billing channel): this one is that bank's number seen as the other party of a transfer. Like
     * Detector.dropWeakCrossBank. The detector's prepaid and loan number sets are local to one detector run and are
     * not on the Detection, so they can not be applied here.
     */
    private static boolean solidElsewhere(Detection detection, String bank, String last4) {
        for (Detection.Entity y : detection.entities) {
            if (y.bank.equals(bank) || !y.last4.equals(last4)) continue;
            if (y.existsConf == Conf.HIGH || y.headerStatements > 0
                    || y.bankChannels.contains(Channel.STATEMENT_CONTENT) || y.bankChannels.contains(Channel.BILLING)) return true;
        }
        return false;
    }

    /** Majority cluster kind of the rows' stored facts; else CREDIT_CARD when the ingest template names a card; else null. */
    static Kind kindOf(List<Row> rows, Map<String, Kind> kindByMessage) {
        Map<Kind, Integer> votes = new EnumMap<>(Kind.class);
        for (Row r : rows) {
            Kind k = kindByMessage == null ? null : kindByMessage.get(r.messageId());
            if (k != null) votes.merge(k, 1, Integer::sum);
        }
        Kind top = null;
        int n = 0;
        for (Map.Entry<Kind, Integer> v : votes.entrySet()) {
            if (v.getValue() > n) {
                top = v.getKey();
                n = v.getValue();
            }
        }
        if (top != null) return top;
        for (Row r : rows) {
            String t = r.template() == null ? "" : r.template().toLowerCase(Locale.ROOT);
            if (t.contains(".cc.") || t.contains("card")) return Kind.CREDIT_CARD;
        }
        return null;
    }
}
