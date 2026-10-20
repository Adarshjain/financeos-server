package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.statement.MissingDetails;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Card statements missing a period, due date or amount. One row per card (not per statement, so a
 * card with a backlog cannot flood the inbox); it clears by itself once nothing is missing, so it
 * offers snooze but no dismiss.
 */
@Component
public class StatementDetailsInboxCollector implements InboxCollector {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final StatementRepository statementRepository;

    public StatementDetailsInboxCollector(StatementRepository statementRepository) {
        this.statementRepository = statementRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        // Newest first (period end, undated last, then created), so the first of a card is the one to open.
        Map<UUID, List<Statement>> byCard = new LinkedHashMap<>();
        for (Statement s : statementRepository.findLiveCreditCardStatementsByUser(userId)) {
            Account card = s.getAccount();
            if (card == null || card.isClosed(today) || MissingDetails.of(s).isEmpty()) {
                continue;
            }
            byCard.computeIfAbsent(card.getId(), k -> new ArrayList<>()).add(s);
        }
        List<InboxItemResponse> rows = new ArrayList<>();
        for (List<Statement> missing : byCard.values()) {
            Statement newest = missing.get(0);
            Account card = newest.getAccount();
            String label = InboxRows.cardLabel(card.getName(), card.primaryLast4());
            String subtitle = missing.size() == 1
                    ? label + " · " + monthOf(newest) + " statement · " + MissingDetails.of(newest).stream()
                            .map(MissingDetails::words).collect(Collectors.joining(", "))
                    : label + " · " + missing.size() + " statements";
            String href = "/ds/accounts/" + card.getId() + "/statements?statement=" + newest.getId();
            rows.add(InboxRows.item(InboxKinds.statementDetailsMissingKey(card.getId()), InboxKinds.STATEMENT_DETAILS_MISSING,
                    SEVERITY_WARNING, SECTION_ACT_NOW, "Statement details missing", subtitle, href, null, null,
                    List.of(InboxRows.open(href), InboxRows.snooze()),
                    InboxRefsResponse.ofStatement(newest.getId(), card.getId())));
        }
        return rows;
    }

    private static String monthOf(Statement s) {
        LocalDate d = s.getPeriodEnd() != null ? s.getPeriodEnd()
                : s.getPeriodStart() != null ? s.getPeriodStart()
                : s.getCreatedAt() == null ? null : LocalDate.ofInstant(s.getCreatedAt(), AppTime.zone());
        return d == null ? "A" : MONTH.format(d);
    }
}
