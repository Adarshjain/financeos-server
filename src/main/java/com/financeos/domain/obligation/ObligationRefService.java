package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanCharge;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanChargeType;
import com.financeos.domain.loan.LoanEvent;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanEventType;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reverse lookup + re-pointing for the direct transaction FKs held by loan/lending rows
 * ({@code lendings.transaction_id}, {@code loan_payments/loan_events/loan_charges.transaction_id}).
 *
 * <p>All reads go through JPA entities carrying {@code @Filter("userFilter")}, so results are
 * tenant-scoped. One query per table per call — never per transaction.
 */
@Service
@Transactional(readOnly = true)
public class ObligationRefService {

    private final LendingRepository lendingRepository;
    private final LoanPaymentRepository loanPaymentRepository;
    private final LoanEventRepository loanEventRepository;
    private final LoanChargeRepository loanChargeRepository;

    public ObligationRefService(LendingRepository lendingRepository,
                                LoanPaymentRepository loanPaymentRepository,
                                LoanEventRepository loanEventRepository,
                                LoanChargeRepository loanChargeRepository) {
        this.lendingRepository = lendingRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanChargeRepository = loanChargeRepository;
    }

    /** Batch: transactionId → its obligation refs (transactions with none are absent from the map). */
    public Map<UUID, List<ObligationRef>> refsFor(Collection<UUID> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ObligationRef>> map = new HashMap<>();

        for (Lending l : lendingRepository.findWithCounterpartyByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(l.getTransaction().getId(), k -> new ArrayList<>()).add(toRef(l));
        }
        for (LoanPayment p : loanPaymentRepository.findWithLoanByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(p.getTransaction().getId(), k -> new ArrayList<>()).add(toRef(p));
        }
        for (LoanEvent e : loanEventRepository.findWithLoanByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(e.getTransaction().getId(), k -> new ArrayList<>()).add(toRef(e));
        }
        for (LoanCharge c : loanChargeRepository.findWithLoanByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(c.getTransaction().getId(), k -> new ArrayList<>()).add(toRef(c));
        }
        return map;
    }

    public List<ObligationRef> refsFor(UUID transactionId) {
        if (transactionId == null) {
            return List.of();
        }
        return refsFor(List.of(transactionId)).getOrDefault(transactionId, List.of());
    }

    public boolean hasRefs(UUID transactionId) {
        return !refsFor(transactionId).isEmpty();
    }

    /**
     * Move every loan/lending reference from {@code from} to {@code to} (transaction merge).
     * Callers validate compatibility first (see {@link #checkMergeCompatibility}); this method only
     * re-points and flushes so the UPDATEs hit the DB before the caller deletes {@code from}
     * (the FKs are {@code ON DELETE SET NULL}, so ordering matters).
     *
     * @return labels of the re-pointed refs, for the audit log
     */
    @Transactional
    public List<String> repoint(Transaction from, Transaction to) {
        List<String> moved = new ArrayList<>();

        List<Lending> lendings = lendingRepository.findWithCounterpartyByTransactionIdIn(List.of(from.getId()));
        for (Lending l : lendings) {
            l.setTransaction(to);
            moved.add(toRef(l).label());
        }
        if (!lendings.isEmpty()) {
            lendingRepository.saveAll(lendings);
            lendingRepository.flush();
        }

        List<LoanPayment> payments = loanPaymentRepository.findWithLoanByTransactionIdIn(List.of(from.getId()));
        for (LoanPayment p : payments) {
            p.setTransaction(to);
            moved.add(toRef(p).label());
        }
        if (!payments.isEmpty()) {
            loanPaymentRepository.saveAll(payments);
            loanPaymentRepository.flush();
        }

        List<LoanEvent> events = loanEventRepository.findWithLoanByTransactionIdIn(List.of(from.getId()));
        for (LoanEvent e : events) {
            e.setTransaction(to);
            moved.add(toRef(e).label());
        }
        if (!events.isEmpty()) {
            loanEventRepository.saveAll(events);
            loanEventRepository.flush();
        }

        List<LoanCharge> charges = loanChargeRepository.findWithLoanByTransactionIdIn(List.of(from.getId()));
        for (LoanCharge c : charges) {
            c.setTransaction(to);
            moved.add(toRef(c).label());
        }
        if (!charges.isEmpty()) {
            loanChargeRepository.saveAll(charges);
            loanChargeRepository.flush();
        }

        return moved;
    }

    /**
     * Decide whether {@code deleted}'s refs may be carried onto {@code kept} during a merge.
     *
     * <ul>
     *   <li>No refs on {@code deleted} → nothing to do.</li>
     *   <li>Different DEBIT/CREDIT type → the direction rule (lent↔DEBIT, borrowed↔CREDIT, loan rows
     *       DEBIT) would break → reject.</li>
     *   <li>{@code deleted} has a LOAN_* ref and {@code kept} has any ref → loan rows are 1:1 → reject.</li>
     *   <li>{@code deleted} has LENDING refs and {@code kept} has a LOAN_* ref → cross-module exclusivity → reject.</li>
     *   <li>LENDING + LENDING on the same-type transaction → fine (split bill).</li>
     * </ul>
     *
     * @return null when compatible, otherwise the human-readable reason for a 400
     */
    public String checkMergeCompatibility(Transaction kept, Transaction deleted) {
        List<ObligationRef> deletedRefs = refsFor(deleted.getId());
        if (deletedRefs.isEmpty()) {
            return null;
        }
        if (kept.getType() != deleted.getType()) {
            return "The transaction being merged away is linked to a loan/lending record ("
                    + deletedRefs.get(0).label() + ") and the kept transaction has the opposite direction; unlink it first.";
        }
        List<ObligationRef> keptRefs = refsFor(kept.getId());
        if (keptRefs.isEmpty()) {
            return null;
        }
        boolean deletedHasLoan = deletedRefs.stream().anyMatch(r -> r.kind() != ObligationKind.LENDING);
        boolean keptHasLoan = keptRefs.stream().anyMatch(r -> r.kind() != ObligationKind.LENDING);
        if (deletedHasLoan || keptHasLoan) {
            return "Both transactions are linked to loan/lending records (" + keptRefs.get(0).label() + " and "
                    + deletedRefs.get(0).label() + "); unlink one before merging.";
        }
        return null;
    }

    // --- label builders -------------------------------------------------------------------

    static ObligationRef toRef(Lending l) {
        String verb = l.getDirection() == LendingDirection.lent ? "Lent" : "Borrowed";
        String cp = l.getCounterparty() != null ? l.getCounterparty().getName() : "";
        return new ObligationRef(ObligationKind.LENDING, l.getId(),
                l.getCounterparty() != null ? l.getCounterparty().getId() : null,
                verb + " · " + cp, l.getAmount());
    }

    static ObligationRef toRef(LoanPayment p) {
        String seq = p.getInstallmentSeq() != null ? "EMI #" + p.getInstallmentSeq() : "EMI";
        return new ObligationRef(ObligationKind.LOAN_PAYMENT, p.getId(), p.getLoan().getId(),
                seq + " · " + p.getLoan().getName(), p.getAmount());
    }

    static ObligationRef toRef(LoanEvent e) {
        return new ObligationRef(ObligationKind.LOAN_EVENT, e.getId(), e.getLoan().getId(),
                humanize(e.getEventType()) + " · " + e.getLoan().getName(), e.getAmount());
    }

    static ObligationRef toRef(LoanCharge c) {
        return new ObligationRef(ObligationKind.LOAN_CHARGE, c.getId(), c.getLoan().getId(),
                humanize(c.getChargeType()) + " · " + c.getLoan().getName(), c.getAmount());
    }

    private static String humanize(LoanEventType t) {
        if (t == null) return "Loan event";
        return switch (t) {
            case rate_change -> "Rate change";
            case prepayment -> "Prepayment";
            case foreclosure -> "Foreclosure";
        };
    }

    private static String humanize(LoanChargeType t) {
        if (t == null) return "Loan charge";
        return switch (t) {
            case processing_fee -> "Processing fee";
            case insurance_premium -> "Insurance premium";
            case foreclosure_charge -> "Foreclosure charge";
            case bounce_charge -> "Bounce charge";
            case late_fee -> "Late fee";
            case legal_valuation -> "Legal / valuation";
            case other -> "Loan charge";
        };
    }
}
