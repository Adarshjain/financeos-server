package com.financeos.domain.transaction.autolink;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.account.BalanceMath;
import com.financeos.domain.notification.bill.BillPaymentCandidates;
import com.financeos.domain.notification.bill.BillPaymentMetrics;
import com.financeos.domain.notification.bill.BillPaymentOrigin;
import com.financeos.domain.notification.bill.BillPaymentService;
import com.financeos.domain.notification.bill.BillPaymentStore;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.bill.PaidSource;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marks an open credit-card bill paid from the bank debit that paid it: a DEBIT on a non-card account of exactly the
 * bill's total due, dated after the statement's last billed day up to three days past the due date, whose text names a
 * card-bill payee (or that is already categorised card_bill). A pairing that is the only fit on both sides is recorded
 * (origin AUTO: the card-side credit is created and linked to the debit, see {@link BillPaymentService}); any other
 * fit is ambiguous: counted and logged (ids only), never recorded and never stored (the Mark paid dialog lists the candidates).
 *
 * <p>Runs in every auto-link pass, scoped or full, and always looks at all of the user's open bills: they are few, and
 * the debit may be far outside the scope. A pairing the user undid is remembered (a BILL_PAYMENT row in auto_link_rejections) and skipped.
 */
@Component
@Order(20)
public class BillPaymentMatcher implements AutoLinker {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentMatcher.class);

    /** How far past the due date a payment may be dated and still be this bill's. */
    static final int DAYS_AFTER_DUE = 3;
    /** Without a due date: how far past the period end. */
    static final int DAYS_AFTER_PERIOD_END = 30;

    private final CardBillService cardBillService;
    private final StatementRepository statementRepository;
    private final BillPaymentCandidates candidates;
    private final BillPaymentService paymentService;
    private final BillPaymentStore paymentStore;
    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore store;
    private final BillPaymentMetrics metrics;
    private final TransactionTemplate isolated;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Ambiguous debits already counted by this JVM, so a repeated pass does not count them again. */
    private final Set<UUID> counted = ConcurrentHashMap.newKeySet();

    public BillPaymentMatcher(CardBillService cardBillService, StatementRepository statementRepository,
                              BillPaymentCandidates candidates, BillPaymentService paymentService, BillPaymentStore paymentStore,
                              AutoLinkCandidateLoader loader, AutoLinkStore store, BillPaymentMetrics metrics,
                              PlatformTransactionManager transactionManager) {
        this.cardBillService = cardBillService;
        this.statementRepository = statementRepository;
        this.candidates = candidates;
        this.paymentService = paymentService;
        this.paymentStore = paymentStore;
        this.loader = loader;
        this.store = store;
        this.metrics = metrics;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of();   // owns no stored suggestions: ambiguity is only counted and logged
    }

    /** One open bill that nothing has paid yet. */
    record OpenBill(UUID statementId, BigDecimal total, LocalDate after, LocalDate until) {
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        ReentrantLock lock = locks.computeIfAbsent(userId, k -> new ReentrantLock());
        lock.lock();
        try {
            return linkLocked(userId);
        } finally {
            lock.unlock();
        }
    }

    /** Runs the matcher alone for the user (worker start-up), as the user, in its own transaction. */
    public AutoLinkOutcome runForUser(UUID userId) {
        return UserContextHelper.callAs(userId, () -> isolated.execute(status -> link(userId)));
    }

    private AutoLinkOutcome linkLocked(UUID userId) {
        List<OpenBill> bills = openBills(userId);
        Map<SuggestionKind, Counts> counts = new EnumMap<>(SuggestionKind.class);
        if (bills.isEmpty()) {
            return AutoLinkOutcome.of(counts);
        }
        LocalDate after = bills.stream().map(OpenBill::after).min(LocalDate::compareTo).orElseThrow();
        LocalDate until = bills.stream().map(OpenBill::until).max(LocalDate::compareTo).orElseThrow();
        List<BillPaymentCandidates.Debit> debits = candidates.load(userId, after, until);
        Set<String> rejections = paymentStore.rejectedPairs(userId);

        Map<UUID, Boolean> signal = new HashMap<>();
        Map<UUID, List<OpenBill>> billsOfDebit = new LinkedHashMap<>();
        Map<UUID, List<BillPaymentCandidates.Debit>> debitsOfBill = new LinkedHashMap<>();
        for (BillPaymentCandidates.Debit d : debits) {
            for (OpenBill b : bills) {
                if (d.amount().compareTo(b.total()) != 0 || !d.date().isAfter(b.after()) || d.date().isAfter(b.until())
                        || rejections.contains(d.id() + "|" + b.statementId())) {
                    continue;
                }
                if (!signal.computeIfAbsent(d.id(), k -> candidates.payeeSignal(d))) {
                    continue;
                }
                billsOfDebit.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(b);
                debitsOfBill.computeIfAbsent(b.statementId(), k -> new ArrayList<>()).add(d);
            }
        }

        int ambiguous = 0;
        int linked = 0;
        int failed = 0;
        for (BillPaymentCandidates.Debit d : debits) {
            List<OpenBill> fit = billsOfDebit.get(d.id());
            if (fit == null) {
                continue;
            }
            if (fit.size() == 1 && debitsOfBill.get(fit.get(0).statementId()).size() == 1) {
                OpenBill bill = fit.get(0);
                try {
                    Boolean recorded = isolated.execute(status -> {
                        if (loader.freeIds(userId, List.of(d.id())).isEmpty()
                                || !paymentStore.bankTxnIdsUsed(userId, List.of(d.id())).isEmpty()) {
                            return false;
                        }
                        // null: the bill was settled meanwhile (checked under the card lock)
                        return paymentService.record(userId, bill.statementId(), new BillPaymentService.Source.ExistingDebit(d.id()),
                                BillPaymentOrigin.AUTO) != null;
                    });
                    if (Boolean.TRUE.equals(recorded)) {
                        linked++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Bill payment match of debit {} to bill {} failed for user {}: {}", d.id(), bill.statementId(), userId, e.getMessage());
                }
                continue;
            }
            ambiguous++;
            if (counted.add(d.id())) {
                metrics.ambiguous();
                log.info("Ambiguous bill payment for user {}: debit {} fits bills {}", userId, d.id(),
                        fit.stream().map(OpenBill::statementId).toList());
            }
        }
        if (linked > 0) {
            counts.put(SuggestionKind.CC_PAYMENT, new Counts(linked, 0));
        }
        log.info("Bill payment matcher finished for user {}: bills={}, linked={}, failed={}, ambiguous={}", userId, bills.size(),
                linked, failed, ambiguous);
        return AutoLinkOutcome.of(counts, failed > 0);
    }

    /** The user's open bills: latest live statement per open card with a total due, no manual mark, no credit paying it yet. */
    List<OpenBill> openBills(UUID userId) {
        List<OpenBill> out = new ArrayList<>();
        for (CardBill bill : cardBillService.listBills(userId)) {
            if (bill.statementId() == null || bill.paidMarkedOn() != null || bill.paidSource() != PaidSource.NONE
                    || bill.totalAmountDue() == null || bill.totalAmountDue().signum() <= 0
                    || bill.status() == BillStatus.PAID || bill.status() == BillStatus.NO_DUE
                    || bill.status() == BillStatus.AWAITING_STATEMENT || bill.periodEnd() == null) {
                continue;
            }
            LocalDate cutoff = BalanceMath.cutoff(bill.periodEnd(), statementRepository.findLastLineDate(bill.statementId()));
            LocalDate until = bill.paymentDueDate() != null ? bill.paymentDueDate().plusDays(DAYS_AFTER_DUE)
                    : bill.periodEnd().plusDays(DAYS_AFTER_PERIOD_END);
            out.add(new OpenBill(bill.statementId(), bill.totalAmountDue(), cutoff, until));
        }
        return out;
    }
}
