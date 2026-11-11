package com.financeos.sms.line;

import com.financeos.api.sms.dto.SmsDtos.AccountLines;
import com.financeos.api.sms.dto.SmsDtos.Last60Days;
import com.financeos.api.sms.dto.SmsDtos.LineView;
import com.financeos.api.sms.dto.SmsDtos.LinesResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.sms.line.SmsAccountQueries.AccountRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The grouped lines screen: accounts with lines, lines with no account, not-mine lines. A fixed number of queries. */
@Service
public class SmsLinesViewService {

    private static final int RECENT_DAYS = 60;

    private final SmsLineRepository lines;
    private final SmsAccountQueries queries;
    private final SenderCatalogue catalogue;
    private final SmsSuggestionService suggestions;

    public SmsLinesViewService(SmsLineRepository lines, SmsAccountQueries queries, SenderCatalogue catalogue,
                                SmsSuggestionService suggestions) {
        this.lines = lines;
        this.queries = queries;
        this.catalogue = catalogue;
        this.suggestions = suggestions;
    }

    @Transactional
    public LinesResponse view(UUID userId) {
        lines.healOrphans(userId);
        if (lines.existsByUserIdAndAccountIdIsNullAndSuggestedAccountIdIsNullAndStateNot(userId, SmsLineState.NOT_MINE)) {
            suggestions.rerunForUser(userId);
        }
        List<SmsLine> all = lines.findByUserId(userId);
        Set<UUID> accountIds = new HashSet<>();
        for (SmsLine l : all) {
            UUID a = target(l);
            if (a != null && l.getState() != SmsLineState.NOT_MINE) accountIds.add(a);
        }
        Map<UUID, AccountRow> accounts = new HashMap<>();
        queries.accounts(userId, accountIds).forEach(a -> accounts.put(a.id(), a));
        Map<UUID, LocalDate> lastDate = new HashMap<>();
        queries.lastTransactionDates(userId, accounts.keySet()).forEach(d -> lastDate.put(d.accountId(), d.date()));
        Map<UUID, long[]> counts = new HashMap<>();
        queries.sourceCountsSince(userId, accounts.keySet(), AppTime.today().minusDays(RECENT_DAYS)).forEach(c -> {
            long[] arr = counts.computeIfAbsent(c.accountId(), k -> new long[3]);
            if (c.source() == TransactionSource.file_upload || c.source() == TransactionSource.gmail_statement) arr[0] += c.count();
            else if (c.source() == TransactionSource.gmail_transaction_alert) arr[1] += c.count();
            else if (c.source() == TransactionSource.manual) arr[2] += c.count();
        });

        Map<UUID, List<LineView>> byAccount = new LinkedHashMap<>();
        List<LineView> unassigned = new ArrayList<>();
        List<LineView> notMine = new ArrayList<>();
        Comparator<SmsLine> order = Comparator.comparing(SmsLine::getProvider).thenComparing(SmsLine::getSender)
                .thenComparing(l -> l.getEnding() == null ? "" : l.getEnding())
                .thenComparing(l -> l.getFamily() == null ? "" : l.getFamily());
        for (SmsLine l : all.stream().sorted(order).toList()) {
            LineView v = toView(l);
            UUID a = target(l);
            if (l.getState() == SmsLineState.NOT_MINE) notMine.add(v);
            else if (a != null && accounts.containsKey(a)) byAccount.computeIfAbsent(a, k -> new ArrayList<>()).add(v);
            else unassigned.add(v);
        }
        List<AccountLines> groups = byAccount.entrySet().stream().map(e -> {
            AccountRow acc = accounts.get(e.getKey());
            long[] c = counts.getOrDefault(e.getKey(), new long[3]);
            return new AccountLines(acc.id(), acc.name(), acc.type(), lastDate.get(acc.id()),
                    new Last60Days(c[0], c[1], c[2]), e.getValue());
        }).sorted(Comparator.comparing(g -> g.name().toLowerCase())).toList();
        return new LinesResponse(groups, unassigned, notMine);
    }

    private static UUID target(SmsLine l) {
        return l.getAccountId() != null ? l.getAccountId() : l.getSuggestedAccountId();
    }

    private LineView toView(SmsLine l) {
        String kind = catalogue.find(l.getSender()).map(SenderCatalogue.Sender::kind).orElse(null);
        return new LineView(l.getId(), l.getSender(), l.getProvider(), kind, l.getEnding(), l.getFamily(), l.getMsgCount(),
                l.getTxnCount(), l.getFirstMsgAt(), l.getLastMsgAt(), l.getState(), l.getSyncFrom(), !l.isSeenByUser(),
                l.getAccountId(), l.getSuggestedAccountId());
    }
}
