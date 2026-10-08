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
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Open cards whose projected statement is past its grace period with nothing imported. */
@Component
public class StatementExpectedInboxCollector implements InboxCollector {

    static final String IMPORT_HREF = "/transactions/import";

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;

    public StatementExpectedInboxCollector(AccountRepository accountRepository, StatementRepository statementRepository) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        for (Account card : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (card.isClosed(today)) {
                continue;
            }
            List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(card.getId());
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
