package com.financeos.domain.categorization;

import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the links a transaction already has say about its category (step 2 of "who sets the category"):
 * transaction links (transfer, card bill, EMI, fee, refund, reversal) and the loan, lending and dividend
 * rows that point at it. Read in batches (a few queries per call, never per row). The mapping is pure so link
 * creation and the arrival/backfill/recategorise paths agree.
 */
@Component
public class LinkCategories {

    /** A category and optional sub-category a link implies. */
    public record Target(String categoryKey, String subcategoryKey) {
    }

    static final Target SELF_TRANSFER = new Target("self_transfer", null);
    static final Target CARD_BILL_PAID = new Target("card_bill", "card_bill.credit_card");
    static final Target CARD_BILL_RECEIVED = new Target("card_bill", null);
    static final Target EMI = new Target("emi", null);
    static final Target FEES = new Target("fees", null);
    static final Target LENT = new Target("lent", null);
    static final Target BORROWED = new Target("borrowed", null);
    static final Target REPAID = new Target("repaid", null);
    static final Target DIVIDENDS = new Target("dividends", null);

    private static final int CHUNK = 500;

    private final TransactionLinkRepository linkRepository;
    private final LendingRepository lendingRepository;
    private final DividendRepository dividendRepository;
    private final LoanPaymentRepository loanPaymentRepository;

    public LinkCategories(TransactionLinkRepository linkRepository, LendingRepository lendingRepository,
                          DividendRepository dividendRepository, LoanPaymentRepository loanPaymentRepository) {
        this.linkRepository = linkRepository;
        this.lendingRepository = lendingRepository;
        this.dividendRepository = dividendRepository;
        this.loanPaymentRepository = loanPaymentRepository;
    }

    /**
     * The category each member of a link takes: transfer both legs self_transfer, card payment card_bill (the
     * bank debit as credit_card), EMI and fee only the installment/fee side (the anchor keeps its own), a
     * reversal the original's category, a refund the original's when {@code refundInherits}.
     */
    public Map<UUID, Target> forLink(TransactionLink link, boolean refundInherits) {
        Map<UUID, Target> out = new HashMap<>();
        Transaction anchor = link.getMembers().stream().filter(TransactionLinkMember::isAnchor)
                .map(TransactionLinkMember::getTransaction).findFirst().orElse(null);
        for (TransactionLinkMember m : link.getMembers()) {
            Transaction t = m.getTransaction();
            Target target = switch (link.getType()) {
                case TRANSFER -> SELF_TRANSFER;
                case CC_PAYMENT -> t.getType() == TransactionType.DEBIT ? CARD_BILL_PAID : CARD_BILL_RECEIVED;
                case EMI -> m.isAnchor() ? null : EMI;
                case FEE -> m.isAnchor() ? null : FEES;
                case REVERSAL -> m.isAnchor() ? null : inherit(anchor);
                case REFUND -> m.isAnchor() || !refundInherits ? null : inherit(anchor);
            };
            if (target != null) {
                out.put(t.getId(), target);
            }
        }
        return out;
    }

    private static Target inherit(Transaction original) {
        return original == null || original.getCategoryKey() == null ? null
                : new Target(original.getCategoryKey(), original.getSubcategoryKey());
    }

    /** What a lending entry linked to a transaction implies: lent, borrowed, or repaid for a settlement. */
    static Target forLending(Lending l) {
        if (l.getKind() == LendingKind.settlement) {
            return REPAID;
        }
        return l.getDirection() == com.financeos.domain.lending.LendingDirection.borrowed ? BORROWED : LENT;
    }

    /** Targets for the given transactions' existing links (transaction links first, then lending, dividend, loan). */
    public Map<UUID, Target> targetsFor(Collection<Transaction> txns, boolean refundInherits) {
        Set<UUID> ids = new HashSet<>();
        for (Transaction t : txns) {
            if (t.getId() != null) {
                ids.add(t.getId());
            }
        }
        Map<UUID, Target> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<UUID> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<UUID> chunk = all.subList(from, Math.min(from + CHUNK, all.size()));
            Set<UUID> linkIds = new HashSet<>(linkRepository.findLinkIdsByMemberTransactionIds(chunk));
            if (!linkIds.isEmpty()) {
                for (TransactionLink link : linkRepository.findWithMembersByIdIn(linkIds)) {
                    forLink(link, refundInherits).forEach((id, target) -> {
                        if (ids.contains(id)) {
                            out.putIfAbsent(id, target);
                        }
                    });
                }
            }
            for (Lending l : lendingRepository.findWithCounterpartyByTransactionIdIn(chunk)) {
                out.putIfAbsent(l.getTransaction().getId(), forLending(l));
            }
            for (Dividend d : dividendRepository.findWithHoldingByTransactionIdIn(chunk)) {
                out.putIfAbsent(d.getTransaction().getId(), DIVIDENDS);
            }
            for (LoanPayment p : loanPaymentRepository.findWithLoanByTransactionIdIn(chunk)) {
                out.putIfAbsent(p.getTransaction().getId(), EMI);
            }
        }
        return out;
    }
}
