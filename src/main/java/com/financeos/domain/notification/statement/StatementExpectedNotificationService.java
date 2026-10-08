package com.financeos.domain.notification.statement;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Your statement hasn't arrived": for a card with statement history, the billing-cycle
 * projection says when the next period should have closed; once that date plus a grace period
 * has passed with no newer statement, say so — once per missed cycle (the marker is the
 * projected period end). This is the dead-mailbox detector in disguise, and the only producer
 * that notices a sender that quietly stopped matching.
 */
@Service
public class StatementExpectedNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(StatementExpectedNotificationService.class);
    static final int GRACE_DAYS = 5;
    private static final int MAX_CYCLES_FORWARD = 24;

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public StatementExpectedNotificationService(AccountRepository accountRepository,
                                                StatementRepository statementRepository,
                                                NotificationPrefsLoader prefsLoader,
                                                NotificationSettingsService settingsService) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "statement-expected";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        NotificationPrefs prefs = prefsLoader.load(userId);
        LocalDate today = AppTime.today();
        if (!prefs.pastSendHour(AppTime.now())) {
            return NotificationOutcome.NONE;
        }
        int evaluated = 0;
        int recorded = 0;
        int sent = 0;
        for (Account card : accountRepository.findByUserIdAndType(userId, AccountType.credit_card)) {
            if (card.isClosed(today)) {
                continue;
            }
            List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(card.getId());
            LocalDate overdueEnd = overduePeriodEnd(statements, today);
            if (overdueEnd == null) {
                continue;
            }
            evaluated++;
            if (Objects.equals(overdueEnd, card.getStatementExpectedNotifiedFor())) {
                continue;
            }
            if (prefs.deliverable(NotificationKind.STATEMENT_EXPECTED)) {
                sent += settingsService.deliver(prefs.settings(), StatementExpectedMessages.missing(card, overdueEnd));
                log.info("Statement expected notification: accountId={}, periodEnd={}", card.getId(), overdueEnd);
            }
            card.setStatementExpectedNotifiedFor(overdueEnd);
            accountRepository.save(card);
            recorded++;
        }
        return new NotificationOutcome(evaluated, recorded, sent);
    }

    // ---------------------------------------------------------------- pure (package-private for tests)

    /**
     * The end of the latest projected period that is already {@value #GRACE_DAYS} days past
     * without a statement, or null when the card is current (or has no statement to project from).
     */
    static LocalDate overduePeriodEnd(List<Statement> statements, LocalDate today) {
        LocalDate lastEnd = statements.stream()
                .map(Statement::getPeriodEnd)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (lastEnd == null) {
            return null;
        }
        BillingCycles cycles = BillingCycles.fromStatements(statements);
        LocalDate overdue = null;
        LocalDate cursor = lastEnd;
        for (int i = 0; i < MAX_CYCLES_FORWARD; i++) {
            LocalDate end = cycles.containing(cursor.plusDays(1)).end();
            if (!end.isAfter(cursor)) {
                break; // defensive: the projection must move forward
            }
            if (!today.isAfter(end.plusDays(GRACE_DAYS))) {
                break;
            }
            overdue = end;
            cursor = end;
        }
        return overdue;
    }
}
