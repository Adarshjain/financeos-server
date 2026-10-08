package com.financeos.domain.notification.emi;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationProducer;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.ReminderSequence;
import com.financeos.domain.notification.bill.BillNotificationKinds;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan EMI reminders, the same shape as card bills: the loan's current installment (the first
 * one without a recorded payment) walks the sequence
 * {@code DUE_n (larger n first) → OVERDUE} using the user's bill offsets; OVERDUE repeats weekly,
 * not daily, because EMIs are usually auto-debited and the nag is "record it", not "pay it".
 * The marker lives on the loan row (installment seq + last kind + date) and resets when the
 * current installment changes. A per-loan mute silences pushes without stopping the marker.
 */
@Service
public class EmiNotificationService implements NotificationProducer {

    private static final Logger log = LoggerFactory.getLogger(EmiNotificationService.class);

    /** An installment this long overdue belongs to a loan whose payments are not being recorded: stay silent. */
    static final int STALE_OVERDUE_DAYS = 45;
    static final int OVERDUE_RENAG_DAYS = 7;

    private final LoanService loanService;
    private final LoanRepository loanRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;

    public EmiNotificationService(LoanService loanService,
                                  LoanRepository loanRepository,
                                  NotificationPrefsLoader prefsLoader,
                                  NotificationSettingsService settingsService) {
        this.loanService = loanService;
        this.loanRepository = loanRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
    }

    @Override
    public String name() {
        return "emi";
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
        for (LoanService.LoanWithSchedule entry : loanService.getAllLoansWithSchedule()) {
            Loan loan = entry.loan();
            if (loan.getStatus() != LoanStatus.active) {
                continue;
            }
            List<InstallmentDto> installments = entry.schedule().installments();
            InstallmentDto current = currentInstallment(installments);
            if (current == null) {
                continue;
            }
            evaluated++;
            boolean sameInstallment = Objects.equals(loan.getLastNotifiedSeq(), current.seq());
            String lastKind = sameInstallment ? loan.getLastNotifiedKind() : null;
            LocalDate lastOn = sameInstallment ? loan.getLastNotifiedOn() : null;
            long days = ChronoUnit.DAYS.between(today, current.dueDate());
            String kind = applicableKind(days, prefs.offsets());
            if (kind == null || (BillNotificationKinds.OVERDUE.equals(kind) && isStale(loan, current, today))) {
                continue;
            }
            if (!shouldSend(kind, lastKind, lastOn, today)) {
                continue;
            }
            NotificationKind preference = preferenceFor(kind);
            if (prefs.deliverable(preference) && !Boolean.TRUE.equals(loan.getNotificationsMuted())) {
                sent += settingsService.deliver(prefs.settings(),
                        EmiMessages.forKind(kind, loan, current, installments.size(), days));
                log.info("EMI notification: kind={}, loanId={}, seq={}", kind, loan.getId(), current.seq());
            }
            loan.setLastNotifiedSeq(current.seq());
            loan.setLastNotifiedKind(kind);
            loan.setLastNotifiedOn(today);
            loanRepository.save(loan);
            recorded++;
        }
        return new NotificationOutcome(evaluated, recorded, sent);
    }

    // ---------------------------------------------------------------- decision logic (pure, package-private for tests)

    /** The first installment without a recorded payment, or null when the loan is fully settled. */
    static InstallmentDto currentInstallment(List<InstallmentDto> installments) {
        for (InstallmentDto installment : installments) {
            if (!"settled".equals(installment.status())) {
                return installment;
            }
        }
        return null;
    }

    /** OVERDUE once the due date has passed; otherwise the smallest configured offset already reached. */
    static String applicableKind(long daysUntilDue, List<Integer> offsets) {
        return ReminderSequence.applicableKind(daysUntilDue, offsets);
    }

    /** Later in the sequence than the marker; OVERDUE additionally repeats weekly. */
    static boolean shouldSend(String kind, String lastKind, LocalDate lastOn, LocalDate today) {
        return ReminderSequence.shouldSend(kind, lastKind, lastOn, today, OVERDUE_RENAG_DAYS);
    }

    /**
     * Overdue installments nobody is recording payments for: older than {@value #STALE_OVERDUE_DAYS}
     * days, or due before the loan was even entered (backfilled history).
     */
    static boolean isStale(Loan loan, InstallmentDto installment, LocalDate today) {
        if (installment.dueDate().isBefore(today.minusDays(STALE_OVERDUE_DAYS))) {
            return true;
        }
        if (loan.getCreatedAt() != null) {
            LocalDate entered = LocalDate.ofInstant(loan.getCreatedAt(), AppTime.zone());
            return installment.dueDate().isBefore(entered);
        }
        return false;
    }

    static NotificationKind preferenceFor(String kind) {
        if (BillNotificationKinds.OVERDUE.equals(kind)) {
            return NotificationKind.EMI_OVERDUE;
        }
        return BillNotificationKinds.isDue(kind) ? NotificationKind.EMI_DUE_REMINDER : null;
    }
}
