package com.financeos.domain.notification.statement;

import com.financeos.core.time.AppTime;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.TransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * After a statement is parsed, the transactions in its period that still wait for review
 * (unreconciled, duplicate-suspect) are what the user asked to be told about. One shot per
 * statement, from the tick rather than the statement event: an uploaded statement commits its
 * row before its lines are linked and deduplicated, so the count is only trustworthy once the
 * ingest is a safe hour old. Not gated by the send hour, like the statement digest itself.
 */
@Service
public class StatementReviewNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(StatementReviewNotificationService.class);
    /** Default minimum age (minutes) before a statement's count is trusted; the e2e profile sets 0. */
    static final int DEFAULT_MIN_AGE_MINUTES = 60;
    static final Duration MAX_AGE = Duration.ofDays(14);
    static final List<ReviewReason> DANGLING_REASONS = List.of(ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT);

    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;
    private final Duration minAge;

    public StatementReviewNotificationService(StatementRepository statementRepository,
                                              TransactionRepository transactionRepository,
                                              NotificationPrefsLoader prefsLoader,
                                              NotificationSettingsService settingsService,
                                              @Value("${notifications.review-digest-min-age-minutes:60}") int minAgeMinutes) {
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
        this.minAge = Duration.ofMinutes(Math.max(0, minAgeMinutes));
    }

    @Override
    public String name() {
        return "statement-review";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        Instant now = AppTime.now().atZone(AppTime.zone()).toInstant();
        List<Statement> candidates = statementRepository.findReviewDigestCandidates(userId, now.minus(MAX_AGE), now.minus(minAge));
        if (candidates.isEmpty()) {
            return NotificationOutcome.NONE;
        }
        NotificationPrefs prefs = prefsLoader.load(userId);
        LocalDate today = AppTime.today();
        int recorded = 0;
        int sent = 0;
        for (Statement statement : candidates) {
            long dangling = 0;
            if (statement.getAccount() != null && statement.getPeriodStart() != null && statement.getPeriodEnd() != null) {
                dangling = transactionRepository.countNeedsReviewInPeriod(statement.getAccount().getId(),
                        statement.getPeriodStart(), statement.getPeriodEnd(), DANGLING_REASONS);
            }
            if (dangling > 0) {
                if (prefs.deliverable(NotificationKind.STATEMENT_REVIEW_DIGEST)) {
                    sent += settingsService.deliver(prefs.settings(), StatementReviewMessages.digest(statement, dangling));
                    log.info("Statement review digest: statementId={}, dangling={}", statement.getId(), dangling);
                }
                recorded++;
            }
            statement.setReviewNotifiedOn(today);
            statementRepository.save(statement);
        }
        return new NotificationOutcome(candidates.size(), recorded, sent);
    }
}
