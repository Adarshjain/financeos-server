package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
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
 * Reverse lookup + re-pointing for the direct transaction FKs held by loan/lending/dividend rows
 * ({@code lendings.transaction_id}, {@code loan_payments/loan_events/loan_charges.transaction_id},
 * {@code dividends.transaction_id}).
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
    private final DividendRepository dividendRepository;

    public ObligationRefService(LendingRepository lendingRepository,
                                LoanPaymentRepository loanPaymentRepository,
                                LoanEventRepository loanEventRepository,
                                LoanChargeRepository loanChargeRepository,
                                DividendRepository dividendRepository) {
        this.lendingRepository = lendingRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanChargeRepository = loanChargeRepository;
        this.dividendRepository = dividendRepository;
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
        for (Dividend d : dividendRepository.findWithHoldingByTransactionIdIn(transactionIds)) {
            map.computeIfAbsent(d.getTransaction().getId(), k -> new ArrayList<>()).add(toRef(d));
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

        List<Dividend> dividends = dividendRepository.findWithHoldingByTransactionIdIn(List.of(from.getId()));
        for (Dividend d : dividends) {
            d.setTransaction(to);
            moved.add(toRef(d).label());
        }
        if (!dividends.isEmpty()) {
            dividendRepository.saveAll(dividends);
            dividendRepository.flush();
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
     *   <li>Refs from different families (LENDING vs LOAN_* vs DIVIDEND) → cross-module exclusivity → reject.</li>
     *   <li>LENDING + LENDING (split bill) or DIVIDEND + DIVIDEND (one credit, several payouts) on the
     *       same-type transaction → fine.</li>
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
            return "The transaction being merged away is linked to a loan/lending/dividend record ("
                    + deletedRefs.get(0).label() + ") and the kept transaction has the opposite direction; unlink it first.";
        }
        List<ObligationRef> keptRefs = refsFor(kept.getId());
        if (keptRefs.isEmpty()) {
            return null;
        }
        Family deletedFamily = familyOf(deletedRefs);
        Family keptFamily = familyOf(keptRefs);
        if (deletedFamily == Family.LOAN || keptFamily == Family.LOAN || deletedFamily != keptFamily) {
            boolean dividendInvolved = deletedFamily == Family.DIVIDEND || keptFamily == Family.DIVIDEND;
            String what = dividendInvolved ? "loan/lending/dividend" : "loan/lending";
            return "Both transactions are linked to " + what + " records (" + keptRefs.get(0).label() + " and "
                    + deletedRefs.get(0).label() + "); unlink one before merging.";
        }
        return null;
    }

    /** Shareable families (LENDING, DIVIDEND) may co-exist with themselves; LOAN never shares. */
    private enum Family { LOAN, LENDING, DIVIDEND }

    private static Family familyOf(List<ObligationRef> refs) {
        boolean anyLoan = refs.stream().anyMatch(r -> r.kind() != ObligationKind.LENDING && r.kind() != ObligationKind.DIVIDEND);
        boolean anyLending = refs.stream().anyMatch(r -> r.kind() == ObligationKind.LENDING);
        boolean anyDividend = refs.stream().anyMatch(r -> r.kind() == ObligationKind.DIVIDEND);
        if (anyLoan || (anyLending && anyDividend)) {
            return Family.LOAN; // exclusive: a loan row, or an impossible mixed set — never shareable
        }
        return anyDividend ? Family.DIVIDEND : Family.LENDING;
    }

    // --- label builders -------------------------------------------------------------------

    static ObligationRef toRef(Lending l) {
        boolean out = l.getDirection() == LendingDirection.lent;
        String verb = l.getKind() == LendingKind.settlement
                ? (out ? "You repaid" : "They repaid")
                : (out ? "Lent" : "Borrowed");
        String cp = l.getCounterparty() != null ? l.getCounterparty().getName() : "";
        return new ObligationRef(ObligationKind.LENDING, l.getId(),
                l.getCounterparty() != null ? l.getCounterparty().getId() : null,
                verb + " · " + cp, l.getAmount());
    }

    static ObligationRef toRef(Dividend d) {
        Instrument instrument = d.getHolding() != null ? d.getHolding().getInstrument() : null;
        String name = "";
        if (instrument != null) {
            String symbol = instrument.getSymbol();
            // Stock tickers are short; MF rows carry an ISIN/AMFI code as symbol, which reads badly.
            boolean tickerLike = symbol != null && !symbol.isBlank() && symbol.trim().length() <= 10;
            name = tickerLike ? symbol.trim() : (instrument.getName() != null ? instrument.getName() : "");
        }
        String verb = d.getType() == DividendType.interest ? "Interest"
                : d.getType() == DividendType.other ? "Payout" : "Dividend";
        return new ObligationRef(ObligationKind.DIVIDEND, d.getId(),
                instrument != null ? instrument.getId() : null,
                verb + " · " + name, d.getAmount());
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
