package com.financeos.sms.ingest;

import com.financeos.api.sms.dto.SmsDtos.ReportAccount;
import com.financeos.api.sms.dto.SmsDtos.ReportAlert;
import com.financeos.api.sms.dto.SmsDtos.ReportResponse;
import com.financeos.api.sms.dto.SmsDtos.ReportTotals;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.sms.line.SmsAccountQueries;
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
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SmsMessageRepository messages;
    private final SmsAccountQueries accountQueries;
    private final SmsPipelineService pipeline;

    public SmsReportService(SmsMessageRepository messages, SmsAccountQueries accountQueries, SmsPipelineService pipeline) {
        this.messages = messages;
        this.accountQueries = accountQueries;
        this.pipeline = pipeline;
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
        balanceGaps(userId, alerts);

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
     * Per line (two lines of one account carry separate balances), READ messages in order: the previous balance, plus or minus the amounts since (including messages that
     * carry no balance), plus or minus this amount, must equal this balance. When it does not, a message may be missing.
     */
    private void balanceGaps(UUID userId, Map<UUID, List<ReportAlert>> alerts) {
        UUID current = null;
        boolean started = false;
        BigDecimal last = null;
        BigDecimal pending = BigDecimal.ZERO;
        Map<UUID, List<ReportAlert>> gaps = new HashMap<>();
        for (Object[] r : messages.findReadForBalanceChain(userId)) {
            UUID accountId = (UUID) r[0];
            UUID lineId = (UUID) r[6];
            if (!started || !java.util.Objects.equals(lineId, current)) {
                started = true;
                current = lineId;
                last = null;
                pending = BigDecimal.ZERO;
            }
            BigDecimal amount = (BigDecimal) r[3];
            TransactionType direction = (TransactionType) r[4];
            BigDecimal balance = (BigDecimal) r[5];
            BigDecimal signed = amount == null || direction == null ? BigDecimal.ZERO
                    : direction == TransactionType.CREDIT ? amount : amount.negate();
            if (balance == null) {
                pending = pending.add(signed);
                continue;
            }
            if (last != null && last.add(pending).add(signed).compareTo(balance) != 0) {
                LocalDate date = r[2] != null ? (LocalDate) r[2] : ((Instant) r[1]).atZone(AppTime.zone()).toLocalDate();
                gaps.computeIfAbsent(accountId, k -> new ArrayList<>()).add(new ReportAlert("BALANCE_GAP",
                        "A message may be missing around " + DMY.format(date), date));
            }
            last = balance;
            pending = BigDecimal.ZERO;
        }
        gaps.forEach((accountId, list) -> {
            List<ReportAlert> recent = list.size() > MAX_BALANCE_ALERTS ? list.subList(list.size() - MAX_BALANCE_ALERTS, list.size()) : list;
            alerts.computeIfAbsent(accountId, k -> new ArrayList<>()).addAll(recent);
        });
    }
}
