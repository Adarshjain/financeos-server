package com.financeos.domain.transaction.autolink;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads, in one query over all of a user's history, the transactions a linker may pair: not a member of
 * any transaction link and not owned by a loan event/payment/charge, lending, dividend or EPF row.
 * Only the columns a linker needs are read.
 */
@Component
public class AutoLinkCandidateLoader {

    private static final String SELECT = "SELECT t.id, a.id, a.type, t.date, t.amount, t.type, "
            + "COALESCE(t.sourcedDescription, t.description) "
            + "FROM Transaction t JOIN t.account a WHERE t.user.id = :userId ";

    private static final String FREE = ""
            + "AND NOT EXISTS (SELECT 1 FROM TransactionLinkMember m WHERE m.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanEvent e WHERE e.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanPayment p WHERE p.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanCharge c WHERE c.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM Lending l WHERE l.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM Dividend d WHERE d.transaction = t) "
            + "AND NOT EXISTS (SELECT 1 FROM EpfEntry x WHERE x.transaction = t) ";

    @PersistenceContext
    private EntityManager entityManager;

    public List<AutoLinkCandidate> load(UUID userId) {
        List<Object[]> rows = entityManager.createQuery(SELECT + FREE + "ORDER BY t.date, t.id", Object[].class)
                .setParameter("userId", userId).getResultList();
        return rows.stream().map(AutoLinkCandidateLoader::toCandidate).toList();
    }

    /** Which of {@code ids} are still free (same filter as {@link #load}); read right before linking a pair. */
    public Set<UUID> freeIds(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Object[]> rows = entityManager.createQuery(SELECT + FREE + "AND t.id IN :ids", Object[].class)
                .setParameter("userId", userId).setParameter("ids", ids).getResultList();
        return rows.stream().map(r -> (UUID) r[0]).collect(Collectors.toSet());
    }

    private static AutoLinkCandidate toCandidate(Object[] r) {
        return new AutoLinkCandidate((UUID) r[0], (UUID) r[1], (AccountType) r[2], (LocalDate) r[3],
                (BigDecimal) r[4], (TransactionType) r[5], (String) r[6]);
    }
}
