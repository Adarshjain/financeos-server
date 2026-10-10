package com.financeos.domain.account;

import com.financeos.domain.statement.StatementRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Live credit-card utilisation, the one definition every surface reads.
 *
 * <p>Sign convention: a card's {@link Account#getCalculatedBalance() calculated balance} is
 * money-signed like every other account — CREDITs add, DEBITs subtract, and an anchoring
 * statement's positive closing balance (amount owed) enters as a negative (see
 * {@link BalanceMath#apply}). So a negative balance is what the card line owes and a positive one
 * is money in credit.
 *
 * <pre>
 *   owed           = max(0, -calculatedBalance)
 *   limit          = account credit limit, else the latest statement's credit limit
 *   utilizationPct = owed / limit * 100, scale 1 (HALF_UP); null when there is no positive limit
 * </pre>
 * A card in credit is 0% utilised (never the absolute value of the credit).
 */
public final class CardUtilization {

    /** Every utilisation figure is reported with one decimal (e.g. 24.1). */
    public static final int SCALE = 1;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CardUtilization() {
    }

    /** What the card line owes right now: the negative part of the balance, as a positive amount. */
    public static BigDecimal owed(@Nullable BigDecimal calculatedBalance) {
        if (calculatedBalance == null || calculatedBalance.signum() >= 0) {
            return BigDecimal.ZERO;
        }
        return calculatedBalance.negate();
    }

    /** The account's limit when it is positive, else the statement's when positive, else null. */
    @Nullable
    public static BigDecimal limit(@Nullable BigDecimal accountLimit, @Nullable BigDecimal statementLimit) {
        if (accountLimit != null && accountLimit.signum() > 0) {
            return accountLimit;
        }
        if (statementLimit != null && statementLimit.signum() > 0) {
            return statementLimit;
        }
        return null;
    }

    /** {@code owed ÷ limit × 100} at {@link #SCALE}; null when neither limit is positive. */
    @Nullable
    public static BigDecimal pct(@Nullable BigDecimal calculatedBalance, @Nullable BigDecimal accountLimit,
                                 @Nullable BigDecimal statementLimit) {
        BigDecimal limit = limit(accountLimit, statementLimit);
        if (limit == null) {
            return null;
        }
        return owed(calculatedBalance).multiply(HUNDRED).divide(limit, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The limit a card's utilisation divides by, the one definition every surface uses (GET
     * /accounts, the cycle summary, the bill digest): the account's own limit when positive, else the
     * credit limit on the latest non-rejected statement that has one (read only when needed), else null.
     */
    @Nullable
    public static BigDecimal creditLimit(Account account, StatementRepository statementRepository) {
        BigDecimal accountLimit = accountLimit(account);
        if (accountLimit != null && accountLimit.signum() > 0) {
            return accountLimit;
        }
        List<BigDecimal> limits = statementRepository.findLatestCreditLimits(account.getId(), PageRequest.of(0, 1));
        return limit(null, limits == null || limits.isEmpty() ? null : limits.get(0));
    }

    /** The account's own credit limit (null for non-cards or when unset). */
    @Nullable
    public static BigDecimal accountLimit(Account account) {
        AccountCreditCardDetails details = account.getCreditCardDetails();
        return details != null ? details.getCreditLimit() : null;
    }
}
