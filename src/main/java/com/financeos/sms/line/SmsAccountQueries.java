package com.financeos.sms.line;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Batched reads over accounts, endings and transactions for the SMS lines. Every query is scoped to the user. */
@Component
public class SmsAccountQueries {

    public record AccountRow(UUID id, String name, AccountType type, String bank, LocalDate closedOn) {}

    /** One ending of an account: a card's last 4, a bank detail's last 4 or an identifier value. */
    public record EndingRow(UUID accountId, String value) {}

    public record SourceCount(UUID accountId, TransactionSource source, long count) {}

    public record LastDate(UUID accountId, LocalDate date) {}

    @PersistenceContext
    private EntityManager em;

    public List<AccountRow> accounts(UUID userId) {
        return em.createQuery("SELECT a.id, a.name, a.type, a.bank, a.closedOn FROM Account a WHERE a.user.id = :u", Object[].class)
                .setParameter("u", userId).getResultList().stream()
                .map(r -> new AccountRow((UUID) r[0], (String) r[1], (AccountType) r[2], (String) r[3], (LocalDate) r[4]))
                .toList();
    }

    public List<AccountRow> accounts(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT a.id, a.name, a.type, a.bank, a.closedOn FROM Account a WHERE a.user.id = :u AND a.id IN :ids", Object[].class)
                .setParameter("u", userId).setParameter("ids", ids).getResultList().stream()
                .map(r -> new AccountRow((UUID) r[0], (String) r[1], (AccountType) r[2], (String) r[3], (LocalDate) r[4]))
                .toList();
    }

    /** Every ending the user's accounts carry: cards, bank details, identifiers. */
    public List<EndingRow> endings(UUID userId) {
        List<EndingRow> out = new java.util.ArrayList<>();
        for (String jpql : List.of(
                "SELECT c.account.id, c.last4 FROM Card c WHERE c.user.id = :u",
                "SELECT b.accountId, b.last4 FROM AccountBankDetails b WHERE b.user.id = :u",
                "SELECT i.account.id, i.value FROM AccountIdentifier i WHERE i.user.id = :u")) {
            for (Object[] r : em.createQuery(jpql, Object[].class).setParameter("u", userId).getResultList()) {
                if (r[0] != null && r[1] != null) {
                    out.add(new EndingRow((UUID) r[0], (String) r[1]));
                }
            }
        }
        return out;
    }

    public List<LastDate> lastTransactionDates(UUID userId, Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT t.account.id, MAX(t.date) FROM Transaction t WHERE t.user.id = :u AND t.account.id IN :ids GROUP BY t.account.id", Object[].class)
                .setParameter("u", userId).setParameter("ids", accountIds).getResultList().stream()
                .map(r -> new LastDate((UUID) r[0], (LocalDate) r[1])).toList();
    }

    public List<SourceCount> sourceCountsSince(UUID userId, Collection<UUID> accountIds, LocalDate from) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("SELECT t.account.id, t.source, COUNT(t) FROM Transaction t WHERE t.user.id = :u AND t.account.id IN :ids AND t.date >= :from GROUP BY t.account.id, t.source", Object[].class)
                .setParameter("u", userId).setParameter("ids", accountIds).setParameter("from", from).getResultList().stream()
                .map(r -> new SourceCount((UUID) r[0], (TransactionSource) r[1], (Long) r[2])).toList();
    }
}
