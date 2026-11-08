package com.financeos.domain.notification.bill;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.builtin.BuiltinCategorizer;
import com.financeos.domain.categorization.builtin.BuiltinMatch;
import com.financeos.domain.categorization.builtin.CategorizeInput;
import com.financeos.domain.categorization.builtin.Step;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkCandidateLoader;
import com.financeos.gmail.extract.Direction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bank debits that could be the payment of a card bill: the user's DEBITs on non-card accounts that nothing
 * owns yet ({@link AutoLinkCandidateLoader#FREE}) and that pay no bill yet, with whether their text names a
 * card-bill payee (the built-in categoriser's link rules, never a second copy of the patterns).
 */
@Component
public class BillPaymentCandidates {

    /** A debit as the matcher and the candidates endpoint see it. */
    public record Debit(UUID id, UUID accountId, String accountName, LocalDate date, BigDecimal amount,
                        String description, String categoryKey) {
    }

    private static final String SELECT = "SELECT t.id, a.id, a.name, t.date, t.amount, COALESCE(t.sourcedDescription, t.description), "
            + "t.categoryKey FROM Transaction t JOIN t.account a WHERE t.user.id = :userId AND t.type = :debit "
            + "AND a.type <> :card AND t.amount IS NOT NULL AND t.date > :after AND t.date <= :until ";

    @PersistenceContext
    private EntityManager entityManager;

    private final BillPaymentStore store;
    private final BuiltinCategorizer categorizer;

    public BillPaymentCandidates(BillPaymentStore store, BuiltinCategorizer categorizer) {
        this.store = store;
        this.categorizer = categorizer;
    }

    /** Free, not-yet-paying debits dated after {@code after} up to and including {@code until}, oldest first. */
    public List<Debit> load(UUID userId, LocalDate after, LocalDate until) {
        List<Object[]> rows = entityManager.createQuery(SELECT + AutoLinkCandidateLoader.FREE + "ORDER BY t.date, t.id", Object[].class)
                .setParameter("userId", userId).setParameter("debit", TransactionType.DEBIT)
                .setParameter("card", AccountType.credit_card).setParameter("after", after).setParameter("until", until)
                .getResultList();
        List<Debit> all = rows.stream().map(r -> new Debit((UUID) r[0], (UUID) r[1], (String) r[2], (LocalDate) r[3],
                (BigDecimal) r[4], (String) r[5], (String) r[6])).toList();
        if (all.isEmpty()) {
            return all;
        }
        Set<UUID> used = store.bankTxnIdsUsed(userId, all.stream().map(Debit::id).toList());
        return all.stream().filter(d -> !used.contains(d.id())).toList();
    }

    /** True when the debit reads as a card-bill payment: a card-bill link rule of the categoriser fires, or it is already categorised card_bill. */
    public boolean payeeSignal(Debit d) {
        if ("card_bill".equals(d.categoryKey())) {
            return true;
        }
        String text = d.description();
        if (text == null || text.isBlank()) {
            return false;
        }
        BuiltinMatch m = categorizer.categorize(new CategorizeInput(text, Direction.DEBIT, d.amount(), null, null, text, null, null));
        return m.step() == Step.LINK && "card_bill".equals(m.categoryKey());
    }
}
