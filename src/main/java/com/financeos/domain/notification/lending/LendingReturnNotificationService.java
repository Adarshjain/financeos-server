package com.financeos.domain.notification.lending;

import com.financeos.core.time.AppTime;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.ReminderSequence;
import com.financeos.domain.notification.bill.BillNotificationKinds;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lending returns: per counterparty, the net outstanding balance (lent minus borrowed over every
 * entry) and the earliest expected return date among the principal entries in that direction.
 * On the day: {@code DUE_0}; once passed: {@code OVERDUE}, repeated weekly. No offsets — people
 * are not bills. The marker sits on the entry whose date defines the obligation.
 */
@Service
public class LendingReturnNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(LendingReturnNotificationService.class);
    static final int OVERDUE_RENAG_DAYS = 7;

    private final LendingRepository lendingRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public LendingReturnNotificationService(LendingRepository lendingRepository,
                                            NotificationPrefsLoader prefsLoader,
                                            NotificationSettingsService settingsService) {
        this.lendingRepository = lendingRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "lending-return";
    }

    /** One counterparty's position: what is outstanding, which way, and the entry that sets the date. */
    record Obligation(UUID counterpartyId, String counterpartyName, BigDecimal outstanding, LendingDirection direction, Lending subject) {
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationOutcome evaluate(UUID userId) {
        NotificationPrefs prefs = prefsLoader.load(userId);
        LocalDate today = AppTime.today();
        if (!prefs.pastSendHour(AppTime.now())) {
            return NotificationOutcome.NONE;
        }
        List<Obligation> due = dueObligations(lendingRepository.findAllWithRefs(), today);
        int recorded = 0;
        int sent = 0;
        for (Obligation obligation : due) {
            Lending subject = obligation.subject();
            long days = ChronoUnit.DAYS.between(today, subject.getExpectedReturnDate());
            String kind = days < 0 ? BillNotificationKinds.OVERDUE : BillNotificationKinds.dueIn(0);
            if (!ReminderSequence.shouldSend(kind, subject.getReturnNotifiedKind(), subject.getReturnNotifiedOn(), today, OVERDUE_RENAG_DAYS)) {
                continue;
            }
            if (prefs.deliverable(NotificationKind.LENDING_RETURN)) {
                sent += settingsService.deliver(prefs.settings(), LendingMessages.forKind(kind, subject,
                        obligation.counterpartyName(), obligation.counterpartyId(), obligation.outstanding(), obligation.direction(), days));
                log.info("Lending return notification: kind={}, counterpartyId={}, lendingId={}", kind, obligation.counterpartyId(), subject.getId());
            }
            subject.setReturnNotifiedKind(kind);
            subject.setReturnNotifiedOn(today);
            lendingRepository.save(subject);
            recorded++;
        }
        return new NotificationOutcome(due.size(), recorded, sent);
    }

    // ---------------------------------------------------------------- pure (package-private for tests)

    /**
     * Counterparties with a non-zero net balance whose earliest expected return date (principal
     * entries in the outstanding direction) is today or earlier.
     */
    static List<Obligation> dueObligations(List<Lending> entries, LocalDate today) {
        Map<UUID, List<Lending>> byCounterparty = new LinkedHashMap<>();
        for (Lending entry : entries) {
            if (entry.getCounterparty() != null) {
                byCounterparty.computeIfAbsent(entry.getCounterparty().getId(), k -> new java.util.ArrayList<>()).add(entry);
            }
        }
        List<Obligation> out = new java.util.ArrayList<>();
        for (List<Lending> group : byCounterparty.values()) {
            BigDecimal net = BigDecimal.ZERO;
            for (Lending entry : group) {
                net = entry.getDirection() == LendingDirection.lent ? net.add(entry.getAmount()) : net.subtract(entry.getAmount());
            }
            if (net.signum() == 0) {
                continue;
            }
            LendingDirection direction = net.signum() > 0 ? LendingDirection.lent : LendingDirection.borrowed;
            Lending subject = group.stream()
                    .filter(e -> e.getDirection() == direction && e.getKind() == LendingKind.principal
                            && e.getExpectedReturnDate() != null && !e.getExpectedReturnDate().isAfter(today))
                    .min(Comparator.comparing(Lending::getExpectedReturnDate).thenComparing(Lending::getEntryDate))
                    .orElse(null);
            if (subject == null) {
                continue;
            }
            out.add(new Obligation(subject.getCounterparty().getId(), subject.getCounterparty().getName(), net.abs(), direction, subject));
        }
        return out;
    }
}
