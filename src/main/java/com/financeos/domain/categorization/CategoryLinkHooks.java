package com.financeos.domain.categorization;

import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLink;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Category side of link events, called where a link is made or dissolved. A link made later sets the
 * category (source LINK) of a row only when it has none or the engine set it (AUTO): never a rule's
 * (user rules beat links) and never the user's. A row whose category a link set and that loses the link
 * goes back through {@link CategoryAssigner#recategorise}. Callers run inside their own transaction.
 */
@Component
public class CategoryLinkHooks {

    private final LinkCategories linkCategories;
    private final CategoryAssigner assigner;
    private final TransactionCategoryWriter writer;
    private final TransactionRepository transactionRepository;

    public CategoryLinkHooks(LinkCategories linkCategories, CategoryAssigner assigner,
                             TransactionCategoryWriter writer, TransactionRepository transactionRepository) {
        this.linkCategories = linkCategories;
        this.assigner = assigner;
        this.writer = writer;
        this.transactionRepository = transactionRepository;
    }

    /** A transaction link was just saved: its members take the category the link implies. */
    public void linked(TransactionLink link, boolean refundInherits) {
        write(linkCategories.forLink(link, refundInherits), link.getMembers().stream()
                .map(m -> m.getTransaction()).toList());
    }

    /** A loan, lending or dividend row was just pointed at this transaction (the row is saved): it takes the row's category. */
    public void linked(Transaction txn) {
        if (txn == null) {
            return;
        }
        write(linkCategories.targetsFor(List.of(txn), true), List.of(txn));
    }

    /**
     * These transactions just lost a link (it was removed, or the row that pointed at them went away):
     * those whose category a link set are re-evaluated (rules, remaining links, engine, else cleared).
     */
    public void released(Collection<Transaction> txns) {
        if (txns == null || txns.isEmpty()) {
            return;
        }
        List<Transaction> viaLink = txns.stream().filter(t -> t != null && t.getCategorySource() == CategorySource.LINK).toList();
        if (viaLink.isEmpty()) {
            return;
        }
        List<Transaction> changed = assigner.recategorise(new ArrayList<>(viaLink), null);
        if (!changed.isEmpty()) {
            transactionRepository.saveAll(changed);
        }
    }

    private void write(Map<UUID, LinkCategories.Target> targets, Collection<Transaction> txns) {
        List<Transaction> changed = new ArrayList<>();
        for (Transaction t : txns) {
            LinkCategories.Target target = targets.get(t.getId());
            if (target == null || !canLink(t)) {
                continue;
            }
            if (writer.set(t, target.categoryKey(), target.subcategoryKey(), CategorySource.LINK, (CategoryRule) null)) {
                changed.add(t);
            }
        }
        if (!changed.isEmpty()) {
            transactionRepository.saveAll(changed);
        }
    }

    /** No category yet, set by the engine, or by an earlier link: not a rule's, not the user's. */
    static boolean canLink(Transaction t) {
        CategorySource s = t.getCategorySource();
        return s == null || s == CategorySource.AUTO || s == CategorySource.LINK;
    }
}
