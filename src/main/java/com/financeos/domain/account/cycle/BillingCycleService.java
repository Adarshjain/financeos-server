package com.financeos.domain.account.cycle;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Billing cycles for a user's credit cards (the only accounts that have one). */
@Service
public class BillingCycleService {

    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;

    public BillingCycleService(AccountRepository accountRepository, StatementRepository statementRepository,
                               TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
    }

    /** One row of a card's cycle table. */
    public record CardCycle(UUID accountId, LocalDate start, LocalDate end) {
    }

    /**
     * Every cycle of every credit card of the user, spanning the card's transactions (earliest
     * effective date through the later of today and its latest one). Cards without transactions
     * contribute nothing.
     */
    @Transactional(readOnly = true)
    public List<CardCycle> cycleTable(UUID userId, LocalDate today) {
        List<CardCycle> out = new ArrayList<>();
        cardCycles(userId).forEach((accountId, cycles) -> {
            LocalDate min = transactionRepository.findMinEffectiveDateByAccountId(accountId);
            if (min == null) {
                return;
            }
            LocalDate max = transactionRepository.findMaxEffectiveDateByAccountId(accountId);
            LocalDate to = max != null && max.isAfter(today) ? max : today;
            for (BillingCycles.Cycle c : cycles.between(min.isAfter(to) ? to : min, to)) {
                out.add(new CardCycle(accountId, c.start(), c.end()));
            }
        });
        return out;
    }

    /** One card's cycles, from its statements. */
    @Transactional(readOnly = true)
    public BillingCycles cyclesFor(UUID accountId) {
        return BillingCycles.fromStatements(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId));
    }

    /** Every credit card of the user → its cycles. */
    @Transactional(readOnly = true)
    public Map<UUID, BillingCycles> cardCycles(UUID userId) {
        Map<UUID, BillingCycles> out = new LinkedHashMap<>();
        for (Account account : accountRepository.findByUserId(userId)) {
            if (account.getType() == AccountType.credit_card) {
                out.put(account.getId(), cyclesFor(account.getId()));
            }
        }
        return out;
    }

    /**
     * For each of the user's credit cards, the cycle {@code cyclesAgo} cycles before the one
     * containing {@code today} (0 = the current cycle, 1 = the previous one).
     */
    @Transactional(readOnly = true)
    public CycleWindows windows(UUID userId, int cyclesAgo, LocalDate today) {
        Map<UUID, Cycle> byCard = new LinkedHashMap<>();
        cardCycles(userId).forEach((accountId, cycles) -> byCard.put(accountId, cycles.cyclesBefore(today, cyclesAgo)));
        return new CycleWindows(byCard);
    }
}
