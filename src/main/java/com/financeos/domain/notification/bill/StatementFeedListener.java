package com.financeos.domain.notification.bill;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.notification.MessageFormat;
import com.financeos.domain.notification.StatementCreatedEvent;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A statement that arrived from Gmail lands in the in-app feed (uploads the user did themselves do not).
 * Independent of push. Never throws: a feed problem must not fail an ingest.
 */
@Component
public class StatementFeedListener {

    private static final Logger log = LoggerFactory.getLogger(StatementFeedListener.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    private final StatementRepository statementRepository;
    private final NotificationFeedService feedService;

    public StatementFeedListener(StatementRepository statementRepository, NotificationFeedService feedService) {
        this.statementRepository = statementRepository;
        this.feedService = feedService;
    }

    /** Own transaction: the statement's lazy account and card details are read after the ingest has committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStatementCreated(StatementCreatedEvent event) {
        UUID previous = UserContext.getCurrentUserId();
        if (previous == null && event.userId() != null) {
            UserContext.setCurrentUserId(event.userId());
        }
        try {
            Statement statement = statementRepository.findById(event.statementId()).orElse(null);
            if (statement == null || statement.getSource() != StatementSource.gmail) {
                return;
            }
            Account account = statement.getAccount();
            String name = account != null && account.getName() != null ? account.getName() : "Statement";
            LocalDate period = statement.getPeriodEnd() != null ? statement.getPeriodEnd()
                    : statement.getPeriodStart() != null ? statement.getPeriodStart() : AppTime.today();
            feedService.record(event.userId(), AppNotificationKind.STATEMENT_IMPORTED,
                    name + " · " + MONTH.format(period) + " statement imported", body(statement),
                    account != null ? AppNotificationRefs.ofAccount(account.getId()) : AppNotificationRefs.NONE,
                    "statement:" + statement.getId());
        } catch (Exception e) {
            log.warn("Statement feed entry failed for statement {}: {}", event.statementId(), e.getMessage());
        } finally {
            if (previous == null) {
                UserContext.clear();
            }
        }
    }

    /** "Bill ₹36,600 due 5 Nov · 42 transactions" for a card with its due figures; just the count otherwise. */
    static String body(Statement statement) {
        List<String> parts = new ArrayList<>();
        StatementCreditCardDetails d = statement.getCreditCardDetails();
        if ("credit_card".equals(statement.getStatementType()) && d != null
                && d.getTotalAmountDue() != null && d.getPaymentDueDate() != null) {
            parts.add("Bill " + MessageFormat.money(d.getTotalAmountDue()) + " due " + MessageFormat.date(d.getPaymentDueDate()));
        }
        Integer count = statement.getTransactionCount();
        if (count != null) {
            parts.add(count + (count == 1 ? " transaction" : " transactions"));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
