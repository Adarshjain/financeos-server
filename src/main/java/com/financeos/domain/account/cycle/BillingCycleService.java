package com.financeos.domain.account.cycle;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepository.EffectiveDateSpan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing cycles for a user's accounts. Credit cards follow their statements; every other
 * account has no billing cycle and uses calendar months. Billing-cycle reports are limited to
 * one account (see the report validator), so cycles of different accounts are never mixed.
 */
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

    /** One row of an account's cycle table. */
    public record AccountCycle(UUID accountId, LocalDate start, LocalDate end) {
    }

    /**
     * Every cycle of every account of the user, spanning the account's transactions (see
     * {@link TransactionRepository#effectiveDateSpan}). Accounts without transactions
     * contribute nothing.
     */
    @Transactional(readOnly = true)
    public List<AccountCycle> cycleTable(UUID userId, LocalDate today) {
        List<AccountCycle> out = new ArrayList<>();
        accountCycles(userId).forEach((accountId, cycles) -> {
            EffectiveDateSpan span = transactionRepository.effectiveDateSpan(accountId, today);
            if (span == null) {
                return;
            }
            for (Cycle c : cycles.between(span.from(), span.to())) {
                out.add(new AccountCycle(accountId, c.start(), c.end()));
            }
        });
        return out;
    }

    /** One account's cycles: a credit card's from its statements, else calendar months. */
    @Transactional(readOnly = true)
    public BillingCycles cyclesFor(Account account) {
        return account.getType() == AccountType.credit_card
                ? BillingCycles.fromStatements(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId()))
                : BillingCycles.calendarMonths();
    }

    /** Every account of the user → its cycles. */
    @Transactional(readOnly = true)
    public Map<UUID, BillingCycles> accountCycles(UUID userId) {
        Map<UUID, BillingCycles> out = new LinkedHashMap<>();
        for (Account account : accountRepository.findByUserId(userId)) {
            out.put(account.getId(), cyclesFor(account));
        }
        return out;
    }

    /**
     * For each of the user's accounts, the cycle {@code cyclesAgo} cycles before the one
     * containing {@code today} (0 = the current cycle, 1 = the previous one).
     */
    @Transactional(readOnly = true)
    public CycleWindows windows(UUID userId, int cyclesAgo, LocalDate today) {
        return windows(userId, cyclesAgo, today, null);
    }

    /**
     * As {@link #windows(UUID, int, LocalDate)}, limited to the account a report is filtered to
     * ({@code accountRef} = its id or its name, case-insensitive); all accounts when null.
     */
    @Transactional(readOnly = true)
    public CycleWindows windows(UUID userId, int cyclesAgo, LocalDate today, String accountRef) {
        Map<UUID, Cycle> byAccount = new LinkedHashMap<>();
        for (Account account : accountRepository.findByUserId(userId)) {
            if (accountRef == null || accountRef.equals(account.getId().toString())
                    || accountRef.equalsIgnoreCase(account.getName())) {
                byAccount.put(account.getId(), cyclesFor(account).cyclesBefore(today, cyclesAgo));
            }
        }
        return new CycleWindows(byAccount);
    }
}
