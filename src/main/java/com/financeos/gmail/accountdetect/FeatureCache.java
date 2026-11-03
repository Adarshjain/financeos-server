package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.MailDerived.AppHit;
import com.financeos.gmail.accountdetect.MailDerived.EventHit;
import com.financeos.gmail.accountdetect.MailDerived.LimitHit;
import com.financeos.gmail.accountdetect.MailDerived.Slot;
import com.financeos.gmail.accountdetect.MailDerived.VariantHit;
import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tab-separated cache of DERIVED features only: bank, hashed cluster and slot keys, role, date, masked last4,
 * label kinds, dictionary variant names, event types, limit buckets and statement-content buckets. No subject, snippet, body,
 * amounts, names or full numbers.
 */
public final class FeatureCache {

    private FeatureCache() {}

    public static void write(Path file, List<MailDerived> mails, List<StatementContent> statements) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (MailDerived m : mails) {
            sb.append("M\t").append(m.bank()).append('\t').append(m.clusterId()).append('\t').append(m.roleVote()).append('\t')
                    .append(m.date() == null ? "" : m.date()).append('\t').append(m.clusterKind() == null ? "" : m.clusterKind()).append('\t');
            // only own-candidate numbers (and wallet/prepaid sightings, which veto a number): other rejected shapes
            // (refs, ids, Aadhaar, OTP, VPA) never reach the cache
            sb.append(join(m.slots().stream().filter(s -> !s.label().rejected() || s.label() == LabelKind.REJECT_PREPAID)
                    .map(s -> s.slotKey() + "," + s.last4() + "," + s.label()).toList())).append('\t');
            sb.append(join(m.variants().stream().map(v -> v.name() + "," + (v.last4() == null ? "" : v.last4())).toList())).append('\t');
            sb.append(join(m.events().stream().map(e -> e.type() + "," + String.join("|", e.last4s())).toList())).append('\t');
            sb.append(join(m.appHits().stream().map(a -> a.bank() + "," + a.last4() + "," + a.label() + "," + n(a.variant())).toList())).append('\t');
            sb.append(m.layout()).append('\t');
            sb.append(join(m.limits().stream().map(l -> l.last4() + "," + l.bucket()).toList())).append('\n');
        }
        for (StatementContent s : statements) {
            sb.append("S\t").append(s.source()).append('\t').append(s.bank()).append('\t').append(n(s.last4())).append('\t')
                    .append(s.kind() == null ? "" : s.kind()).append('\t').append(s.period() == null ? "" : s.period()).append('\t')
                    .append(n(s.variant())).append('\t').append(n(s.limitBucket())).append('\t').append(String.join("|", s.addOnLast4()))
                    .append('\t').append(s.mentionsClosure()).append('\t').append(s.periodEnd() == null ? "" : s.periodEnd())
                    .append('\t').append(n(s.originCluster())).append('\t').append(s.originDate() == null ? "" : s.originDate()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public record Loaded(List<MailDerived> mails, List<StatementContent> statements) {}

    public static Loaded read(Path file) throws IOException {
        List<MailDerived> mails = new ArrayList<>();
        List<StatementContent> stmts = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f[0].equals("M")) {
                List<Slot> slots = new ArrayList<>();
                for (String x : split(f[6])) {
                    String[] p = x.split(",", -1);
                    slots.add(new Slot(p[0], p[1], LabelKind.valueOf(p[2])));
                }
                List<VariantHit> vs = new ArrayList<>();
                for (String x : split(f[7])) {
                    String[] p = x.split(",", -1);
                    vs.add(new VariantHit(p[0], p[1].isEmpty() ? null : p[1]));
                }
                List<EventHit> es = new ArrayList<>();
                for (String x : split(f[8])) {
                    String[] p = x.split(",", -1);
                    es.add(new EventHit(EventType.valueOf(p[0]), p[1].isEmpty() ? List.of() : Arrays.asList(p[1].split("\\|"))));
                }
                List<AppHit> as = new ArrayList<>();
                for (String x : split(f[9])) {
                    String[] p = x.split(",", -1);
                    as.add(new AppHit(p[0], p[1], LabelKind.valueOf(p[2]), p.length > 3 ? e(p[3]) : null));
                }
                mails.add(new MailDerived(f[1], f[2], Role.valueOf(f[3]), f[4].isEmpty() ? null : LocalDate.parse(f[4]),
                        f[5].isEmpty() ? null : Kind.valueOf(f[5]), slots, vs, es, as, f.length > 10 && !f[10].isEmpty() ? Integer.parseInt(f[10]) : 0, limits(f.length > 11 ? f[11] : "")));
            } else if (f[0].equals("S")) {
                stmts.add(new StatementContent(f[1], f[2], e(f[3]), f[4].isEmpty() ? null : Kind.valueOf(f[4]),
                        f[5].isEmpty() ? null : YearMonth.parse(f[5]), e(f[6]), e(f[7]),
                        f[8].isEmpty() ? List.of() : Arrays.asList(f[8].split("\\|")), Boolean.parseBoolean(f[9]),
                        f.length > 10 && !f[10].isEmpty() ? LocalDate.parse(f[10]) : null,
                        f.length > 11 ? e(f[11]) : null, f.length > 12 && !f[12].isEmpty() ? LocalDate.parse(f[12]) : null));
            }
        }
        return new Loaded(mails, stmts);
    }

    private static List<LimitHit> limits(String col) {
        List<LimitHit> out = new ArrayList<>();
        for (String x : split(col)) {
            String[] p = x.split(",", -1);
            out.add(new LimitHit(p[0], p[1]));
        }
        return out;
    }

    private static String join(List<String> l) {
        return String.join(";", l);
    }

    private static List<String> split(String s) {
        return s.isEmpty() ? List.of() : Arrays.asList(s.split(";"));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String e(String s) {
        return s.isEmpty() ? null : s;
    }
}
