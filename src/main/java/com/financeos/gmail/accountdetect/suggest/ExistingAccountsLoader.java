package com.financeos.gmail.accountdetect.suggest;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads one user's accounts, cards and aliases in four queries, each filtered by user explicitly (a job thread has no
 * per-request user filter).
 */
@Component
public class ExistingAccountsLoader {

    private final EntityManager em;

    public ExistingAccountsLoader(EntityManager em) {
        this.em = em;
    }

    public ExistingAccounts load(UUID userId) {
        LocalDate today = AppTime.today();
        Map<UUID, String> bankLast4 = new HashMap<>();
        for (Object[] r : rows("SELECT d.account.id, d.last4 FROM AccountBankDetails d WHERE d.account.user.id = :u", userId)) {
            bankLast4.put((UUID) r[0], blankToNull((String) r[1]));
        }
        Map<UUID, List<ExistingAccounts.CardRef>> cards = new HashMap<>();
        for (Object[] r : rows("SELECT c.account.id, c.id, c.last4, c.closedOn FROM Card c WHERE c.user.id = :u", userId)) {
            cards.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add(new ExistingAccounts.CardRef((UUID) r[1], (String) r[2], r[3] == null || today.isBefore((LocalDate) r[3])));
        }
        Map<UUID, List<ExistingAccounts.Alias>> aliases = new HashMap<>();
        for (Object[] r : rows("SELECT i.account.id, i.value, i.kind FROM AccountIdentifier i WHERE i.user.id = :u", userId)) {
            aliases.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add(new ExistingAccounts.Alias((String) r[1], (AccountIdentifierKind) r[2]));
        }
        List<ExistingAccounts.Acc> accounts = new ArrayList<>();
        for (Object[] r : rows("SELECT a.id, a.name, a.type, a.bank, a.closedOn FROM Account a WHERE a.user.id = :u", userId)) {
            UUID id = (UUID) r[0];
            LocalDate closedOn = (LocalDate) r[4];
            boolean open = closedOn == null || today.isBefore(closedOn);
            accounts.add(new ExistingAccounts.Acc(id, (String) r[1], (AccountType) r[2], blankToNull((String) r[3]), open,
                    bankLast4.get(id), cards.getOrDefault(id, List.of()), aliases.getOrDefault(id, List.of())));
        }
        return new ExistingAccounts(accounts);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String jpql, UUID userId) {
        return em.createQuery(jpql).setParameter("u", userId).getResultList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
