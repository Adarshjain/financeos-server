package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_ACT_NOW;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.notification.statement.StatementExpectedNotificationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Open cards whose projected statement is past its grace period with nothing imported. */
@Component
public class StatementExpectedInboxCollector implements ContextAwareInboxCollector {

    static final String IMPORT_HREF = "/transactions/import";

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;

    public StatementExpectedInboxCollector(AccountRepository accountRepository, StatementRepository statementRepository) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        return collect(userId, today, new InboxContext());
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today, InboxContext context) {
        List<InboxItemResponse> rows = new ArrayList<>();
        List<Account> open = context.creditCards(() -> accountRepository.findByUserIdAndType(userId, AccountType.credit_card))
                .stream().filter(card -> !card.isClosed(today)).toList();
        if (open.isEmpty()) {
            return rows;
        }
        Map<UUID, List<Statement>> byCard = new HashMap<>();
        for (Statement s : statementRepository.findQualifyingCreditCardStatementsIn(open.stream().map(Account::getId).toList())) {
            byCard.computeIfAbsent(s.getAccount().getId(), k -> new ArrayList<>()).add(s);
        }
        for (Account card : open) {
            List<Statement> statements = byCard.getOrDefault(card.getId(), List.of());
            LocalDate overdueEnd = StatementExpectedNotificationService.overduePeriodEnd(statements, today);
            if (overdueEnd == null) {
                continue;
            }
            String href = IMPORT_HREF + "?account=" + card.getId();
            String title = InboxRows.cardLabel(card.getName(), card.primaryLast4()) + " statement missing";
            String subtitle = "Expected by " + InboxRows.date(overdueEnd.plusDays(StatementExpectedNotificationService.GRACE_DAYS))
                    + " · period ended " + InboxRows.date(overdueEnd);
            rows.add(InboxRows.item(InboxKinds.statementExpectedKey(card.getId(), overdueEnd), InboxKinds.STATEMENT_EXPECTED,
                    SEVERITY_WARNING, SECTION_ACT_NOW, title, subtitle, href, null, overdueEnd,
                    List.of(InboxActionResponse.navigate("upload", "Upload", href), InboxRows.snooze()),
                    InboxRefsResponse.ofAccount(card.getId())));
        }
        return rows;
    }
}
