package com.financeos.domain.account;

import com.financeos.api.account.dto.BalancePointResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An account's daily end-of-day balances for the last {@code days} days ending today (the account
 * tile's trend line).
 *
 * <p>The series walks back from the account's current calculated balance (exactly what the account
 * shows) by its signed day totals, read in one grouped query. The balance of day D is the current
 * balance minus every transaction dated after D (future-dated ones included, as the current balance
 * includes them). An anchored account is anchored on its latest statement's closing balance; days
 * before that statement still walk back by transactions rather than re-anchoring on older
 * statements, so the line is continuous and its last point always equals the shown balance. A
 * broker's value comes from holdings and prices, not transactions, so a broker has no series
 * (empty list).
 */
@Service
@Transactional(readOnly = true)
public class AccountBalanceSeriesService {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 365;

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    public AccountBalanceSeriesService(AccountService accountService, TransactionRepository transactionRepository) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    /** Oldest first, {@code days} points ending today; 404 for a missing or another user's account. */
    public List<BalancePointResponse> series(UUID accountId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new ValidationException("days must be between " + MIN_DAYS + " and " + MAX_DAYS);
        }
        Account account = accountService.findOwnedAccount(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (account.getType() == AccountType.broker) {
            return List.of();
        }
        LocalDate today = AppTime.today();
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, BigDecimal> byDate = new HashMap<>();
        for (TransactionRepository.DailySumProjection row
                : transactionRepository.sumSignedByDateAfter(accountId, first)) {
            if (row.getDate() != null && row.getTotal() != null) {
                byDate.merge(row.getDate(), row.getTotal(), BigDecimal::add);
            }
        }
        return walkBack(account.getCalculatedBalance(), byDate, first, today);
    }

    /**
     * End-of-day balances from {@code first} to {@code today}, given the current balance and the
     * signed totals of every date after {@code first}.
     */
    static List<BalancePointResponse> walkBack(BigDecimal current, Map<LocalDate, BigDecimal> totalsAfterFirst,
                                               LocalDate first, LocalDate today) {
        BigDecimal balance = current != null ? current : BigDecimal.ZERO;
        // End of today: undo anything dated after today.
        for (Map.Entry<LocalDate, BigDecimal> e : totalsAfterFirst.entrySet()) {
            if (e.getKey().isAfter(today)) {
                balance = balance.subtract(e.getValue());
            }
        }
        List<BalancePointResponse> points = new ArrayList<>();
        for (LocalDate d = today; !d.isBefore(first); d = d.minusDays(1)) {
            points.add(new BalancePointResponse(d, balance));
            balance = balance.subtract(totalsAfterFirst.getOrDefault(d, BigDecimal.ZERO));
        }
        java.util.Collections.reverse(points);
        return points;
    }
}
