package com.financeos.sms.ingest;

import com.financeos.api.sms.dto.SmsDtos.ReportAccount;
import com.financeos.api.sms.dto.SmsDtos.ReportAlert;
import com.financeos.api.sms.dto.SmsDtos.ReportResponse;
import com.financeos.api.sms.dto.SmsDtos.ReportTotals;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.sms.line.SmsAccountQueries;
import com.financeos.sms.line.SmsLine;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.line.SmsLineState;
import com.financeos.sms.read.SmsReadStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shadow report: what was stored, read and what would happen, per account, with the self-checks. */
@Service
public class SmsReportService {

    static final int MAX_BALANCE_ALERTS = 20;
    /** A transaction from another source must be dated more than this many days after the provider's last message ... */
    static final int SILENT_GAP_DAYS = 3;
    /** ... and within this many days of today. */
    static final int SILENT_WINDOW_DAYS = 60;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SmsMessageRepository messages;
    private final SmsAccountQueries accountQueries;
    private final SmsPipelineService pipeline;
    private final SmsLineRepository lines;

    public SmsReportService(SmsMessageRepository messages, SmsAccountQueries accountQueries, SmsPipelineService pipeline,
                            SmsLineRepository lines) {
        this.messages = messages;
        this.accountQueries = accountQueries;
        this.pipeline = pipeline;
        this.lines = lines;
    }

    /** Mutable counters for one account. */
    private static final class Counts {
        long wouldCreate, wouldAttach, created, attached, duplicates, needsConfirm, couldNotRead;
        Instant first, last;
    }

    @Transactional(readOnly = true)
    public ReportResponse report(UUID userId) {
        long stored = 0, read = 0, needsConfirm = 0, couldNotRead = 0, ignored = 0;
        long wouldCreate = 0, wouldAttach = 0, created = 0, attached = 0, duplicates = 0;
        Map<UUID, Counts> perAccount = new LinkedHashMap<>();
        for (Object[] r : messages.reportCounts(userId)) {
            UUID accountId = (UUID) r[0];
            SmsReadStatus status = (SmsReadStatus) r[1];
            SmsOutcome outcome = (SmsOutcome) r[2];
            long n = (Long) r[3];
            stored += n;
            switch (status) {
                case READ -> read += n;
                case NEEDS_CONFIRM -> needsConfirm += n;
                case COULD_NOT_READ -> couldNotRead += n;
                case IGNORED -> ignored += n;
            }
            if (outcome != null) {
                switch (outcome) {
                    case SHADOW_CREATE -> wouldCreate += n;
                    case SHADOW_ATTACH -> wouldAttach += n;
                    case CREATED -> created += n;
                    case ATTACHED -> attached += n;
                    case DUPLICATE -> duplicates += n;
                    default -> { }
                }
            }
            if (accountId == null) {
                continue;
            }
            Counts c = perAccount.computeIfAbsent(accountId, k -> new Counts());
            if (status == SmsReadStatus.NEEDS_CONFIRM) c.needsConfirm += n;
            if (status == SmsReadStatus.COULD_NOT_READ) c.couldNotRead += n;
            if (outcome != null) {
                switch (outcome) {
                    case SHADOW_CREATE -> c.wouldCreate += n;
                    case SHADOW_ATTACH -> c.wouldAttach += n;
                    case CREATED -> c.created += n;
                    case ATTACHED -> c.attached += n;
                    case DUPLICATE -> c.duplicates += n;
                    default -> { }
                }
            }
            Instant first = (Instant) r[4];
            Instant last = (Instant) r[5];
            if (first != null && (c.first == null || first.isBefore(c.first))) c.first = first;
            if (last != null && (c.last == null || last.isAfter(c.last))) c.last = last;
        }

        Map<UUID, List<ReportAlert>> alerts = new HashMap<>();
        for (Object[] r : messages.latestCouldNotRead(userId)) {
            UUID accountId = (UUID) r[0];
            Counts c = accountId == null ? null : perAccount.get(accountId);
            if (c != null && c.couldNotRead > 0) {
                alerts.computeIfAbsent(accountId, k -> new ArrayList<>()).add(new ReportAlert("COULD_NOT_READ",
                        c.couldNotRead + (c.couldNotRead == 1 ? " message" : " messages") + " could not be read",
                        ((Instant) r[1]).atZone(AppTime.zone()).toLocalDate()));
            }
        }
        List<SmsLine> userLines = lines.findByUserId(userId);
        balanceGaps(userId, userLines, alerts);
        silentLines(userId, userLines, perAccount.keySet(), alerts);

        Map<UUID, String> names = new HashMap<>();
        accountQueries.accounts(userId, perAccount.keySet()).forEach(a -> names.put(a.id(), a.name()));
        List<ReportAccount> accounts = perAccount.entrySet().stream()
                .filter(e -> names.containsKey(e.getKey()))
                .map(e -> {
                    Counts c = e.getValue();
                    return new ReportAccount(e.getKey(), names.get(e.getKey()), c.first, c.last, c.wouldCreate, c.wouldAttach,
                            c.created, c.attached, c.duplicates, c.needsConfirm, c.couldNotRead,
                            alerts.getOrDefault(e.getKey(), List.of()));
                })
                .sorted(Comparator.comparing(ReportAccount::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new ReportResponse(pipeline.isPostingEnabled(), new ReportTotals(stored, read, needsConfirm, couldNotRead, ignored,
                wouldCreate, wouldAttach, created, attached, duplicates), accounts);
    }

    /**
     * READ messages in time order, chained per account and catalogue provider: a provider's lines of one account carry
     * one balance (HSBC texts one account as a 3-digit ending in one format and a 4-digit ending in another), lines of
     * different providers carry their own. The previous balance, plus or minus the amounts since (including messages
     * that carry no balance), plus or minus this amount, must equal this balance. When it does not, a message may be
     * missing.
     */
    private void balanceGaps(UUID userId, List<SmsLine> userLines, Map<UUID, List<ReportAlert>> alerts) {
        Map<UUID, String> providerOfLine = new HashMap<>();
        for (SmsLine l : userLines) {
            providerOfLine.put(l.getId(), l.getProvider() == null ? "" : l.getProvider().toLowerCase(java.util.Locale.ROOT));
        }
        // chain key -> {last balance (null before the first), amounts since it}
        Map<String, BigDecimal[]> chains = new HashMap<>();
        Map<UUID, List<ReportAlert>> gaps = new HashMap<>();
        for (Object[] r : messages.findReadForBalanceChain(userId)) {
            UUID accountId = (UUID) r[0];
            UUID lineId = (UUID) r[6];
            String key = accountId + "|" + (lineId == null ? "" : providerOfLine.getOrDefault(lineId, lineId.toString()));
            BigDecimal[] chain = chains.computeIfAbsent(key, k -> new BigDecimal[] {null, BigDecimal.ZERO});
            BigDecimal amount = (BigDecimal) r[3];
            TransactionType direction = (TransactionType) r[4];
            BigDecimal balance = (BigDecimal) r[5];
            BigDecimal signed = amount == null || direction == null ? BigDecimal.ZERO
                    : direction == TransactionType.CREDIT ? amount : amount.negate();
            if (balance == null) {
                chain[1] = chain[1].add(signed);
                continue;
            }
            if (chain[0] != null && chain[0].add(chain[1]).add(signed).compareTo(balance) != 0) {
                LocalDate date = r[2] != null ? (LocalDate) r[2] : ((Instant) r[1]).atZone(AppTime.zone()).toLocalDate();
                gaps.computeIfAbsent(accountId, k -> new ArrayList<>()).add(new ReportAlert("BALANCE_GAP",
                        "A message may be missing around " + DMY.format(date), date));
            }
            chain[0] = balance;
            chain[1] = BigDecimal.ZERO;
        }
        gaps.forEach((accountId, list) -> {
            List<ReportAlert> recent = list.size() > MAX_BALANCE_ALERTS ? list.subList(list.size() - MAX_BALANCE_ALERTS, list.size()) : list;
            alerts.computeIfAbsent(accountId, k -> new ArrayList<>()).addAll(recent);
        });
    }

    /**
     * Silence is judged per account and catalogue provider (a provider's lines of one account are one feed). Raised
     * when the account has a transaction from another source (not a text message) dated more than
     * {@link #SILENT_GAP_DAYS} days after the provider's last message of the account and within the last
     * {@link #SILENT_WINDOW_DAYS} days: the account moved on and the provider's texts did not. One alert per account
     * and provider; only lines that are ON count.
     */
    private void silentLines(UUID userId, List<SmsLine> userLines, java.util.Set<UUID> reportedAccounts,
                             Map<UUID, List<ReportAlert>> alerts) {
        Map<UUID, Instant> newest = new HashMap<>();
        for (Object[] r : messages.newestPerLine(userId)) {
            newest.put((UUID) r[0], (Instant) r[1]);
        }
        // account|provider -> {display name, newest message}
        Map<String, Object[]> feeds = new LinkedHashMap<>();
        for (SmsLine l : userLines) {
            Instant last = newest.get(l.getId());
            if (l.getState() != SmsLineState.ON || l.getAccountId() == null || last == null
                    || !reportedAccounts.contains(l.getAccountId())) {
                continue;
            }
            String provider = l.getProvider() != null && !l.getProvider().isBlank() ? l.getProvider() : l.getSender();
            Object[] feed = feeds.computeIfAbsent(l.getAccountId() + "|" + provider.toLowerCase(java.util.Locale.ROOT),
                    k -> new Object[] {l.getAccountId(), provider, last});
            if (last.isAfter((Instant) feed[2])) {
                feed[2] = last;
            }
        }
        if (feeds.isEmpty()) {
            return;
        }
        LocalDate today = AppTime.today();
        Map<UUID, LocalDate> otherNewest = new HashMap<>();
        for (SmsAccountQueries.LastDate d : accountQueries.lastNonSmsDates(userId,
                feeds.values().stream().map(f -> (UUID) f[0]).collect(java.util.stream.Collectors.toSet()),
                today.minusDays(SILENT_WINDOW_DAYS), today)) {
            otherNewest.put(d.accountId(), d.date());
        }
        for (Object[] feed : feeds.values()) {
            UUID accountId = (UUID) feed[0];
            LocalDate lastSms = ((Instant) feed[2]).atZone(AppTime.zone()).toLocalDate();
            LocalDate other = otherNewest.get(accountId);
            if (other == null || !other.isAfter(lastSms.plusDays(SILENT_GAP_DAYS))) {
                continue;
            }
            alerts.computeIfAbsent(accountId, k -> new ArrayList<>()).add(new ReportAlert("SILENT_LINE",
                    feed[1] + " has sent nothing since " + DMY.format(lastSms) + ", but this account has newer transactions", lastSms));
        }
    }
}
