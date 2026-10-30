package com.financeos.domain.transaction.autolink;

import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanService.UnsettledInstallment;
import com.financeos.domain.obligation.MatchRules;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.PairWriter.Planned;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Settles a loan installment with a bank DEBIT when that is the only fit both ways. The fit is the one the
 * loan match-suggestions view uses ({@link MatchRules#loanInstallmentFits}: the loan's payment account, the EMI
 * within max(Rs 100, 1%), the due date within 7 days), but over every unsettled installment of every loan, not
 * only those due within a week. An installment with exactly one candidate that is a candidate of no other
 * installment (of any loan) is recorded as an AUTO loan payment through the same path Confirm uses. A loan
 * with no payment account is never auto-linked. Anything ambiguous is left to the match-suggestions view.
 */
@Component
@Order(20)
public class EmiAutoLinker implements AutoLinker {

    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore store;
    private final LoanService loanService;
    private final PairWriter writer;

    public EmiAutoLinker(AutoLinkCandidateLoader loader, AutoLinkStore store, LoanService loanService,
                         PlatformTransactionManager transactionManager) {
        this.loader = loader;
        this.store = store;
        this.loanService = loanService;
        this.writer = new PairWriter(loader, transactionManager);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of();
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        // Both sets are read fresh here, after the transfer pass.
        List<UnsettledInstallment> installments = loanService.getUnsettledInstallments();
        if (installments.isEmpty()) {
            return AutoLinkOutcome.of(Map.of());
        }
        List<Pair> pairs = plan(installments, loader.load(userId), store.loadRejectionKeys(userId));
        List<Planned> planned = pairs.stream()
                .map(p -> new Planned(p.transactionId(), "installment " + p.installment().seq() + " of loan " + p.installment().loanId(),
                        () -> loanService.addAutoPayment(p.installment().loanId(), p.installment().seq(), p.transactionId())))
                .toList();
        return writer.write(userId, SuggestionKind.EMI, "EMI linker", planned);
    }

    // ---------------------------------------------------------------- pure planning

    record Pair(UnsettledInstallment installment, UUID transactionId) {
    }

    static List<Pair> plan(List<UnsettledInstallment> installments, List<AutoLinkCandidate> candidates, Set<String> rejections) {
        Map<UUID, List<AutoLinkCandidate>> debitsByAccount = new HashMap<>();
        for (AutoLinkCandidate c : candidates) {
            if (c.type() == TransactionType.DEBIT) {
                debitsByAccount.computeIfAbsent(c.accountId(), k -> new ArrayList<>()).add(c);
            }
        }

        Map<UnsettledInstallment, List<UUID>> fitsOfInstallment = new LinkedHashMap<>();
        Map<UUID, Integer> installmentsOfTxn = new HashMap<>();
        for (UnsettledInstallment inst : installments) {
            if (inst.paymentAccountId() == null) {
                continue;
            }
            UUID ref = LoanService.installmentRef(inst.loanId(), inst.seq());
            List<UUID> fits = new ArrayList<>();
            for (AutoLinkCandidate c : debitsByAccount.getOrDefault(inst.paymentAccountId(), List.of())) {
                if (MatchRules.loanInstallmentFits(inst.emi(), inst.dueDate(), inst.paymentAccountId(), c.accountId(), c.amount(), c.date())
                        && !AutoLinkStore.isRejected(rejections, SuggestionKind.EMI, c.id(), ref)) {
                    fits.add(c.id());
                    installmentsOfTxn.merge(c.id(), 1, Integer::sum);
                }
            }
            fitsOfInstallment.put(inst, fits);
        }

        List<Pair> out = new ArrayList<>();
        for (Map.Entry<UnsettledInstallment, List<UUID>> e : fitsOfInstallment.entrySet()) {
            if (e.getValue().size() == 1 && installmentsOfTxn.get(e.getValue().get(0)) == 1) {
                out.add(new Pair(e.getKey(), e.getValue().get(0)));
            }
        }
        return out;
    }
}
