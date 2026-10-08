package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_CRITICAL;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.notification.lending.LendingReturnNotificationService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Money due back (or due to be paid back) per counterparty: overdue, or due within a week, using
 * the same net-balance rule the push producer applies.
 */
@Component
public class LendingInboxCollector implements InboxCollector {

    static final int DUE_SOON_DAYS = 7;

    private final LendingRepository lendingRepository;

    public LendingInboxCollector(LendingRepository lendingRepository) {
        this.lendingRepository = lendingRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        // The push producer asks "due by today"; the inbox looks a week ahead with the same net-balance rule.
        for (LendingReturnNotificationService.Obligation obligation
                : LendingReturnNotificationService.dueObligations(lendingRepository.findAllWithRefs(), today.plusDays(DUE_SOON_DAYS))) {
            Lending subject = obligation.subject();
            long days = ChronoUnit.DAYS.between(today, subject.getExpectedReturnDate());
            if (days > DUE_SOON_DAYS) {
                continue;
            }
            boolean lent = obligation.direction() == LendingDirection.lent;
            // The amount is its own field on the row; keep it out of the title.
            String title = lent
                    ? obligation.counterpartyName() + " owes you"
                    : "You owe " + obligation.counterpartyName();
            String subtitle = days < 0
                    ? InboxRows.dueText(days) + " · expected back " + InboxRows.date(subject.getExpectedReturnDate())
                    : InboxRows.dueText(days) + " · " + (lent ? "lent on " : "borrowed on ") + InboxRows.date(subject.getEntryDate());
            // Owed to you: land on the ledger's share sheet (the push copy says "Tap to share the ledger").
            String href = "/loans/lendings/" + obligation.counterpartyId() + (lent ? "?export=1" : "");
            rows.add(InboxRows.item(InboxKinds.lendingKey(obligation.counterpartyId()), InboxKinds.LENDING,
                    days < 0 ? SEVERITY_CRITICAL : SEVERITY_WARNING, SECTION_ACT_NOW, title, subtitle, href,
                    obligation.outstanding(), subject.getExpectedReturnDate(),
                    List.of(InboxRows.open(href), InboxRows.snooze()),
                    InboxRefsResponse.ofCounterparty(obligation.counterpartyId())));
        }
        return rows;
    }
}
