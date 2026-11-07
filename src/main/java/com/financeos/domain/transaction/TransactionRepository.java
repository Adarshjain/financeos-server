package com.financeos.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, TransactionRepositoryCustom {

    interface TransactionBalanceProjection {
        UUID getId();

        java.math.BigDecimal getBalance();

        /** The bank's printed balance, only on the row that starts a mismatch with our running balance. */
        java.math.BigDecimal getBankBalance();
    }

    @Override
    @EntityGraph(attributePaths = { "tags.tag", "appliedRule", "account" })
    Page<Transaction> findAll(Pageable pageable);


    /**
     * Category backfill: a page of the user's never-categorised rows (no category and no category source, so a
     * row the user cleared is skipped), in id order, after {@code afterId}.
     */
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.categoryKey IS NULL AND t.categorySource IS NULL "
            + "AND t.id > :afterId ORDER BY t.id")
    List<Transaction> findBackfillPageAfter(@Param("userId") UUID userId, @Param("afterId") UUID afterId,
                                            org.springframework.data.domain.Pageable page);

    /** First page of {@link #findBackfillPageAfter}. */
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.categoryKey IS NULL AND t.categorySource IS NULL "
            + "ORDER BY t.id")
    List<Transaction> findBackfillFirstPage(@Param("userId") UUID userId, org.springframework.data.domain.Pageable page);

    @EntityGraph(attributePaths = { "tags.tag", "appliedRule", "account", "card", "card.cardholder", "reviewReasons" })
    List<Transaction> findAllByIdIn(List<UUID> ids);

    @EntityGraph(attributePaths = { "tags.tag", "appliedRule", "account", "card", "card.cardholder", "reviewReasons" })
    List<Transaction> findAllByIdInAndUserId(List<UUID> ids, UUID userId);

    @EntityGraph(attributePaths = { "tags.tag", "appliedRule", "account" })
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByDateRange(@Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT t FROM Transaction t JOIN t.tags tc JOIN tc.tag c WHERE c.name = :category ORDER BY t.date DESC")
    List<Transaction> findByCategory(@Param("category") String category);

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByUserIdAndSourceMessageId(UUID userId, String sourceMessageId);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.source = :source AND t.reviewType = :reviewType")
    List<Transaction> findByAccountIdAndSourceAndReviewType(
            @Param("accountId") UUID accountId,
            @Param("source") TransactionSource source,
            @Param("reviewType") ReviewType reviewType);

    /**
     * Card bills: every CREDIT dated after {@code afterDate} (a statement's last billed day,
     * {@code BalanceMath.cutoff}) — payments, refunds, cashback, excluded rows included: exactly
     * the credits the account balance subtracts, so the bill owes what the balance owes.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId " +
           "AND t.type = com.financeos.domain.transaction.TransactionType.CREDIT AND t.date > :afterDate")
    java.math.BigDecimal sumCreditsAfter(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** {@link #findByAccountIdAndDateRange} with each row's card in the same query: the matchers read every row's card last4. */
    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.card WHERE t.account.id = :accountId AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findLedgerByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Reward engine: fetch by effective date (settlement date when present, else transaction date). */
    @EntityGraph(attributePaths = { "tags.tag", "card.cardholder" })
    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId " +
            "AND COALESCE(t.settlementDate, t.date) BETWEEN :startDate AND :endDate " +
            "AND t.amount IS NOT NULL")
    List<Transaction> findForRewardEvaluation(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** {@link #findForRewardEvaluation} for several accounts at once (batched totals). Both skip foreign charges with no rupee amount yet. */
    @EntityGraph(attributePaths = { "tags.tag", "card.cardholder" })
    @Query("SELECT t FROM Transaction t WHERE t.account.id IN :accountIds " +
            "AND COALESCE(t.settlementDate, t.date) BETWEEN :startDate AND :endDate " +
            "AND t.amount IS NOT NULL")
    List<Transaction> findForRewardEvaluationIn(
            @Param("accountIds") java.util.Collection<UUID> accountIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<Transaction> findByAppliedRuleId(UUID appliedRuleId);

    List<Transaction> findByAppliedRuleIdIn(Collection<UUID> appliedRuleIds);

    interface RuleMatchCandidate {
        UUID getId();

        String getSourcedDescription();

        /** Who set the category now (USER rows are matched but never overwritten); null when uncategorised. */
        CategorySource getCategorySource();
    }

    /**
     * Candidates for category-rule matching: only ingested transactions (rules never match
     * manual descriptions). Lightweight projection because the rule predicate runs in Java
     * (REGEX can't be pushed to SQL).
     */
    @Query("SELECT t.id AS id, t.sourcedDescription AS sourcedDescription, t.categorySource AS categorySource "
           + "FROM Transaction t "
           + "WHERE t.user.id = :userId AND t.sourcedDescription IS NOT NULL "
           + "ORDER BY t.date DESC, t.createdAt DESC")
    List<RuleMatchCandidate> findRuleMatchCandidates(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.account.id = :accountId")
    java.math.BigDecimal findTotalTransactionSumByAccountId(@Param("accountId") UUID accountId);

    /** One account's signed day totals (credits positive) for every date after {@code afterDate}. */
    interface DailySumProjection {
        LocalDate getDate();

        java.math.BigDecimal getTotal();
    }

    /**
     * The account's signed transaction total per date after {@code afterDate} (future dates
     * included), over the same rows the calculated balance is made of: every transaction of the
     * account, excluded ones included. One grouped query for a whole balance series.
     */
    @Query("SELECT t.date AS date, SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END) AS total "
            + "FROM Transaction t WHERE t.account.id = :accountId AND t.date > :afterDate AND t.amount IS NOT NULL GROUP BY t.date")
    List<DailySumProjection> sumSignedByDateAfter(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :afterDate")
    java.math.BigDecimal findPostAnchorTransactionSumByAccountId(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    /**
     * Card unbilled spend: every DEBIT dated after {@code afterDate} (a statement's last billed day,
     * {@code BalanceMath.cutoff}), excluded rows included — the account balance counts them.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId " +
           "AND t.type = com.financeos.domain.transaction.TransactionType.DEBIT AND t.date > :afterDate")
    java.math.BigDecimal sumDebitsAfter(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    interface AccountSumProjection {
        UUID getAccountId();
        java.math.BigDecimal getTotal();
    }

    /** {@link #findTotalTransactionSumByAccountId} for several accounts at once; an account without transactions has no row. */
    @Query("SELECT t.account.id AS accountId, SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END) AS total "
           + "FROM Transaction t WHERE t.account.id IN :accountIds AND t.amount IS NOT NULL GROUP BY t.account.id")
    List<AccountSumProjection> findTotalTransactionSumsByAccountIds(@Param("accountIds") java.util.Collection<UUID> accountIds);

    interface AccountLastDateProjection {
        UUID getAccountId();
        LocalDate getLastDate();
    }

    interface AccountMonthProjection {
        UUID getAccountId();
        Integer getYr();
        Integer getMo();
    }

    /** The distinct (year, month) of the transactions of several accounts, one grouped query (every transaction counts). */
    @Query("SELECT t.account.id AS accountId, extract(year from t.date) AS yr, extract(month from t.date) AS mo "
           + "FROM Transaction t WHERE t.account.id IN :accountIds "
           + "GROUP BY t.account.id, extract(year from t.date), extract(month from t.date)")
    List<AccountMonthProjection> findTransactionMonthsByAccountIds(@Param("accountIds") java.util.Collection<UUID> accountIds);

    /**
     * The latest transaction date per account for several accounts at once. Counts every transaction of the
     * account (the same set the balance sums: no excluded/pending filter); an account without one has no row.
     */
    @Query("SELECT t.account.id AS accountId, MAX(t.date) AS lastDate FROM Transaction t "
           + "WHERE t.account.id IN :accountIds GROUP BY t.account.id")
    List<AccountLastDateProjection> findLastTransactionDatesByAccountIds(@Param("accountIds") java.util.Collection<UUID> accountIds);


    interface BalanceAggregatesProjection {
        java.math.BigDecimal getTotalSum();
        java.math.BigDecimal getPostAnchorSum();
    }

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END), 0) AS totalSum, " +
           "COALESCE(SUM(CASE WHEN t.date > :afterDate THEN (CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END) ELSE 0 END), 0) AS postAnchorSum " +
           "FROM Transaction t WHERE t.account.id = :accountId")
    BalanceAggregatesProjection findBalanceAggregatesByAccountId(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    /**
     * The transactions an account's calculated balance is made of, newest first with a stable
     * tiebreak (created time, then id). The WHERE is exactly the balance rule of
     * {@link #findBalanceAggregatesByAccountId} and the batch balance query: every transaction of the
     * account (excluded ones included, no review or source filter), restricted to
     * {@code date > afterDate} when the balance is anchored on a statement whose last billed day
     * is {@code afterDate} ({@code BalanceMath.cutoff}: its period end, or its latest line's date
     * when earlier); a null {@code afterDate} (no anchor) lists them all.
     */
    @Query(value = "SELECT t FROM Transaction t WHERE t.account.id = :accountId " +
                   "AND (:afterDate IS NULL OR t.date > :afterDate) " +
                   "ORDER BY t.date DESC, t.createdAt DESC, t.id DESC",
           countQuery = "SELECT COUNT(t) FROM Transaction t WHERE t.account.id = :accountId " +
                        "AND (:afterDate IS NULL OR t.date > :afterDate)")
    Page<Transaction> findBalanceTransactions(@Param("accountId") UUID accountId,
                                              @Param("afterDate") LocalDate afterDate,
                                              Pageable pageable);

    /** One row of {@link #findBalanceTransactionRows}: the fields a balance breakdown lists. */
    interface BalanceTransactionRow {
        UUID getId();

        LocalDate getDate();

        String getDescription();

        String getSourcedDescription();

        TransactionType getType();

        java.math.BigDecimal getAmount();

        boolean getExcluded();
    }

    /**
     * Every transaction {@link #findBalanceTransactions} lists (same WHERE, same order), as flat
     * rows without loading entities — for ordering the whole list by another column.
     */
    @Query("SELECT t.id AS id, t.date AS date, t.description AS description, " +
           "t.sourcedDescription AS sourcedDescription, t.type AS type, t.amount AS amount, " +
           "t.isTransactionExcluded AS excluded " +
           "FROM Transaction t WHERE t.account.id = :accountId AND (:afterDate IS NULL OR t.date > :afterDate) " +
           "ORDER BY t.date DESC, t.createdAt DESC, t.id DESC")
    List<BalanceTransactionRow> findBalanceTransactionRows(@Param("accountId") UUID accountId,
                                                           @Param("afterDate") LocalDate afterDate);

    /** The category of one transaction, by its fixed-list name. */
    interface BalanceTransactionCategoryRow {
        UUID getTransactionId();

        String getName();
    }

    /** The category name of every categorised transaction {@link #findBalanceTransactionRows} lists. */
    @Query("SELECT t.id AS transactionId, c.name AS name FROM Transaction t "
            + "JOIN com.financeos.domain.category.TxnCategory c ON c.key = t.categoryKey "
            + "WHERE t.account.id = :accountId "
            + "AND (:afterDate IS NULL OR t.date > :afterDate)")
    List<BalanceTransactionCategoryRow> findBalanceTransactionCategoryNames(@Param("accountId") UUID accountId,
                                                                           @Param("afterDate") LocalDate afterDate);

    /**
     * Credits and debits (every non-CREDIT type, as the balance signs them) among the transactions
     * {@link #findBalanceTransactions} lists, with how many of them are excluded transactions.
     */
    interface BalanceMovementsProjection {
        Long getCreditCount();

        java.math.BigDecimal getCreditSum();

        Long getDebitCount();

        java.math.BigDecimal getDebitSum();

        Long getExcludedCount();
    }

    /** Totals of {@link #findBalanceTransactions} (same WHERE): credit − debit sums to the balance's movement. */
    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN 1 ELSE 0 END), 0) AS creditCount, " +
           "COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE 0 END), 0) AS creditSum, " +
           "COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN 0 ELSE 1 END), 0) AS debitCount, " +
           "COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN 0 ELSE t.amount END), 0) AS debitSum, " +
           "COALESCE(SUM(CASE WHEN t.isTransactionExcluded = true THEN 1 ELSE 0 END), 0) AS excludedCount " +
           "FROM Transaction t WHERE t.account.id = :accountId AND (:afterDate IS NULL OR t.date > :afterDate)")
    BalanceMovementsProjection findBalanceMovements(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    /** The user's bank-account (non-card) transactions dated in the window, newest first. */
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.card IS NULL "
            + "AND t.account.type = com.financeos.domain.account.AccountType.bank_account "
            + "AND t.date BETWEEN :minDate AND :maxDate AND t.amount IS NOT NULL ORDER BY t.date DESC, t.id")
    List<Transaction> findBankTransactionsBetween(
            @Param("userId") UUID userId,
            @Param("minDate") LocalDate minDate,
            @Param("maxDate") LocalDate maxDate);

    /** Latest transaction date across the user's accounts of one type — how far tracked data reaches. */
    @Query("SELECT MAX(t.date) FROM Transaction t WHERE t.account.type = :type")
    LocalDate findMaxDateByAccountType(@Param("type") com.financeos.domain.account.AccountType type);

    /**
     * CREDITs on receiving-capable accounts inside an amount band and date window (dividend receipt
     * candidates). The account is fetched because the response embeds it.
     */
    @Query("SELECT t FROM Transaction t JOIN FETCH t.account a WHERE t.type = com.financeos.domain.transaction.TransactionType.CREDIT " +
           "AND a.type IN :accountTypes AND t.amount BETWEEN :minAmount AND :maxAmount AND t.date BETWEEN :minDate AND :maxDate")
    List<Transaction> findCreditCandidates(
            @Param("accountTypes") java.util.Collection<com.financeos.domain.account.AccountType> accountTypes,
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
            @Param("minDate") LocalDate minDate,
            @Param("maxDate") LocalDate maxDate);

    /**
     * CREDITs whose narration mentions a dividend keyword — a coarse SQL prefilter (DIV / IDCW
     * substrings); the token-level check in {@code DividendMatcher.hasKeyword} refines it in Java.
     */
    @Query("SELECT t FROM Transaction t JOIN FETCH t.account a WHERE t.type = com.financeos.domain.transaction.TransactionType.CREDIT " +
           "AND a.type IN :accountTypes AND t.date BETWEEN :fromDate AND :toDate AND (" +
           "UPPER(t.sourcedDescription) LIKE '%DIV%' OR UPPER(t.description) LIKE '%DIV%' OR " +
           "UPPER(t.sourcedDescription) LIKE '%IDCW%' OR UPPER(t.description) LIKE '%IDCW%')")
    List<Transaction> findDividendLikeCredits(
            @Param("accountTypes") java.util.Collection<com.financeos.domain.account.AccountType> accountTypes,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Gmail cleanup: the account's alert rows before the date that no statement has a line for (unreconciled). */
    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.user.id = :userId AND t.source = com.financeos.domain.transaction.TransactionSource.gmail_transaction_alert AND t.date < :beforeDate "
            + "AND NOT EXISTS (SELECT 1 FROM StatementTransaction st WHERE st.id.transactionId = t.id)")
    List<Transaction> findUnreconciledAlertsBeforeDate(
            @Param("accountId") UUID accountId,
            @Param("userId") UUID userId,
            @Param("beforeDate") LocalDate beforeDate);

    /** Transactions tagged NOT_ON_STATEMENT by this statement. */
    List<Transaction> findByNotOnStatementId(UUID notOnStatementId);

    /** Statement review digest: transactions still waiting for review inside a statement period, by reason. */
    @Query("SELECT COUNT(DISTINCT t.id) FROM Transaction t JOIN t.reviewReasons r WHERE t.account.id = :accountId AND t.reviewType = com.financeos.domain.transaction.ReviewType.NEEDS_REVIEW AND t.date BETWEEN :from AND :to AND r IN :reasons")
    long countNeedsReviewInPeriod(@Param("accountId") UUID accountId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("reasons") java.util.Collection<ReviewReason> reasons);

    @Query("SELECT MIN(t.date) FROM Transaction t WHERE t.account.id = :accountId")
    LocalDate findMinDateByAccountId(@Param("accountId") UUID accountId);

    /** Reward reports: earliest effective date (settlement date when present, else transaction date). */
    @Query("SELECT MIN(COALESCE(t.settlementDate, t.date)) FROM Transaction t WHERE t.account.id = :accountId")
    LocalDate findMinEffectiveDateByAccountId(@Param("accountId") UUID accountId);

    /** Reward reports: latest effective date, so future-dated settlements are still reported. */
    @Query("SELECT MAX(COALESCE(t.settlementDate, t.date)) FROM Transaction t WHERE t.account.id = :accountId")
    LocalDate findMaxEffectiveDateByAccountId(@Param("accountId") UUID accountId);

    /** An account's activity span by effective date, both ends inclusive. */
    record EffectiveDateSpan(LocalDate from, LocalDate to) {
    }

    /**
     * Every effective date on the account: the earliest through the later of {@code today} and
     * the latest (future-dated settlements included). Null when the account has no transactions.
     */
    default EffectiveDateSpan effectiveDateSpan(UUID accountId, LocalDate today) {
        LocalDate min = findMinEffectiveDateByAccountId(accountId);
        if (min == null) {
            return null;
        }
        LocalDate max = findMaxEffectiveDateByAccountId(accountId);
        LocalDate to = max != null && max.isAfter(today) ? max : today;
        return new EffectiveDateSpan(min.isAfter(to) ? to : min, to);
    }

    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.account.id = :accountId " +
           "AND (:from IS NULL OR t.date >= :from) " +
           "AND (:to IS NULL OR t.date <= :to) " +
           "AND (:currentCardId IS NULL OR (t.card IS NOT NULL AND t.card.id = :currentCardId))")
    List<Transaction> findForBulkReattribute(
            @Param("userId") UUID userId,
            @Param("accountId") UUID accountId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("currentCardId") UUID currentCardId);

    long countByCardId(UUID cardId);

    /** Card id, transaction count, first and last date: one row per card of the account that has transactions. */
    @Query("SELECT t.card.id, COUNT(t), MIN(t.date), MAX(t.date) FROM Transaction t "
            + "WHERE t.card.account.id = :accountId GROUP BY t.card.id")
    List<Object[]> summarizeByCard(@Param("accountId") UUID accountId);

    /**
     * Card id and net spend (DEBIT minus CREDIT) per card of the account within [from, to]. Credits that are
     * the counterpart of a CC_PAYMENT link (a bill payment landing on the card) are not refunds, so they are left out.
     */
    @Query("SELECT t.card.id, SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN -t.amount ELSE t.amount END) "
            + "FROM Transaction t WHERE t.card.account.id = :accountId AND t.date >= :from AND t.date <= :to AND t.amount IS NOT NULL "
            + "AND NOT (t.type = com.financeos.domain.transaction.TransactionType.CREDIT AND EXISTS ("
            + "SELECT 1 FROM TransactionLinkMember m WHERE m.transaction = t "
            + "AND m.link.type = com.financeos.domain.transaction.link.LinkType.CC_PAYMENT)) "
            + "GROUP BY t.card.id")
    List<Object[]> netSpendByCard(@Param("accountId") UUID accountId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Moves every transaction of one card onto another; returns how many moved. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Transaction t SET t.card = :to WHERE t.card = :from")
    int reassignCard(@Param("from") com.financeos.domain.account.card.Card from, @Param("to") com.financeos.domain.account.card.Card to);

    /** Inbox: how many of the user's transactions sit in the review queue. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user.id = :userId AND t.reviewType = :reviewType")
    long countByUserIdAndReviewType(@Param("userId") UUID userId, @Param("reviewType") ReviewType reviewType);

    /** Inbox: of those, how many carry one review reason (e.g. UNRECONCILED). */
    @Query("SELECT COUNT(DISTINCT t.id) FROM Transaction t JOIN t.reviewReasons r WHERE t.user.id = :userId AND t.reviewType = :reviewType AND r = :reason")
    long countByUserIdAndReviewTypeAndReason(@Param("userId") UUID userId, @Param("reviewType") ReviewType reviewType,
                                             @Param("reason") ReviewReason reason);

    interface TypeTotalProjection {
        TransactionType getType();

        java.math.BigDecimal getTotal();
    }

    /** The account's credit and debit totals (amounts are positive) for dates in {@code from}..{@code to}. */
    @Query("SELECT t.type AS type, SUM(t.amount) AS total FROM Transaction t "
            + "WHERE t.account.id = :accountId AND t.date BETWEEN :from AND :to AND t.amount IS NOT NULL GROUP BY t.type")
    List<TypeTotalProjection> sumByTypeBetween(@Param("accountId") UUID accountId,
                                               @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface CategoryTotalProjection {
        String getName();

        java.math.BigDecimal getTotal();
    }

    /** Per category name (the fixed list), the account's totals of one direction for dates in {@code from}..{@code to}; uncategorised rows have no row here. */
    @Query("SELECT c.name AS name, SUM(t.amount) AS total FROM Transaction t "
            + "JOIN com.financeos.domain.category.TxnCategory c ON c.key = t.categoryKey "
            + "WHERE t.account.id = :accountId AND t.type = :type AND t.date BETWEEN :from AND :to AND t.amount IS NOT NULL GROUP BY c.name")
    List<CategoryTotalProjection> sumByCategoryBetween(@Param("accountId") UUID accountId,
                                                       @Param("type") TransactionType type,
                                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Credits in the window whose category is the fixed-list "interest" category. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId "
            + "AND t.type = com.financeos.domain.transaction.TransactionType.CREDIT AND t.date BETWEEN :from AND :to "
            + "AND t.categoryKey = 'interest'")
    java.math.BigDecimal sumInterestCreditsBetween(@Param("accountId") UUID accountId,
                                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The account's latest transaction of a source, by date then creation time. */
    Optional<Transaction> findFirstByAccount_IdAndSourceOrderByDateDescCreatedAtDesc(UUID accountId, TransactionSource source);

    /** One uncategorised row, light: what grouping by who was paid needs. */
    interface UncategorizedRow {
        UUID getId();

        LocalDate getDate();

        String getSourcedDescription();

        String getDescription();

        TransactionType getType();

        java.math.BigDecimal getAmount();

        String getMcc();
    }

    /** Every row of the user with no category (a user-cleared row too), no entities loaded. */
    @Query("SELECT t.id AS id, t.date AS date, t.sourcedDescription AS sourcedDescription, t.description AS description, "
            + "t.type AS type, t.amount AS amount, t.mcc AS mcc FROM Transaction t "
            + "WHERE t.user.id = :userId AND t.categoryKey IS NULL")
    List<UncategorizedRow> findUncategorizedRows(@Param("userId") UUID userId);

    /**
     * Whether the user has any row with no category: the same condition as {@link #findUncategorizedRows}
     * (so exactly the rows Sort them lists), as one cheap EXISTS-style probe.
     */
    boolean existsByUser_IdAndCategoryKeyIsNull(UUID userId);

    /** Cheap freshness token of the user's uncategorised set: how many rows, and the newest edit among them. */
    interface UncategorizedFingerprint {
        long getCount();

        java.time.Instant getMaxUpdatedAt();
    }

    @Query("SELECT COUNT(t) AS count, MAX(t.updatedAt) AS maxUpdatedAt FROM Transaction t "
            + "WHERE t.user.id = :userId AND t.categoryKey IS NULL")
    UncategorizedFingerprint uncategorizedFingerprint(@Param("userId") UUID userId);

    interface CategoryPairRow {
        String getCategoryKey();

        String getSubcategoryKey();
    }

    /** The user's own picks of one direction, newest pick first (one row per category/sub pair). */
    @Query("SELECT t.categoryKey AS categoryKey, t.subcategoryKey AS subcategoryKey FROM Transaction t "
            + "WHERE t.user.id = :userId AND t.type = :type AND t.categoryKey IS NOT NULL "
            + "AND t.categorySource = com.financeos.domain.transaction.CategorySource.USER "
            + "GROUP BY t.categoryKey, t.subcategoryKey ORDER BY MAX(t.categorySetAt) DESC")
    List<CategoryPairRow> findRecentUserCategories(@Param("userId") UUID userId, @Param("type") TransactionType type,
                                                   Pageable page);

    interface CategoryCountRow {
        String getCategoryKey();

        String getSubcategoryKey();

        long getCount();
    }

    /** Rows of the user per category/sub pair (null keys = uncategorised), whole ledger. */
    @Query("SELECT t.categoryKey AS categoryKey, t.subcategoryKey AS subcategoryKey, COUNT(t) AS count "
            + "FROM Transaction t WHERE t.user.id = :userId GROUP BY t.categoryKey, t.subcategoryKey")
    List<CategoryCountRow> countByCategory(@Param("userId") UUID userId);

    /** A transaction as the Sort-them group list draws it, account and card resolved in the same query. */
    interface GroupTransactionRow {
        UUID getId();

        LocalDate getDate();

        String getDescription();

        String getSourcedDescription();

        java.math.BigDecimal getAmount();

        TransactionType getType();

        TransactionSource getSource();

        TransactionChannel getChannel();

        UUID getAccountId();

        String getAccountName();

        String getAccountLast4();

        String getCardLast4();

        String getCardholderName();
    }

    @Query("SELECT t.id AS id, t.date AS date, t.description AS description, t.sourcedDescription AS sourcedDescription, "
            + "t.amount AS amount, t.type AS type, t.source AS source, t.channel AS channel, a.id AS accountId, "
            + "a.name AS accountName, bd.last4 AS accountLast4, c.last4 AS cardLast4, ch.personName AS cardholderName "
            + "FROM Transaction t JOIN t.account a LEFT JOIN a.bankDetails bd LEFT JOIN t.card c LEFT JOIN c.cardholder ch "
            + "WHERE t.user.id = :userId AND t.id IN :ids")
    List<GroupTransactionRow> findGroupTransactionRows(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /** A transaction at a bank, enough to match a Gmail mail by amount, direction and date. */
    interface BankTransactionBrief {
        UUID getAccountId();
        String getAccountName();
        String getBank();
        LocalDate getDate();
        java.math.BigDecimal getAmount();
        TransactionType getType();
        String getDescription();
    }

    /** The user's transactions at one bank within a date span and one of these amounts, one query for all groups. */
    @Query("SELECT a.id AS accountId, a.name AS accountName, a.bank AS bank, t.date AS date, t.amount AS amount, t.type AS type, t.description AS description "
            + "FROM Transaction t JOIN t.account a WHERE t.user.id = :userId AND a.bank IN :banks AND t.date BETWEEN :fromDate AND :toDate AND t.amount IN :amounts")
    List<BankTransactionBrief> findBankBriefs(@Param("userId") UUID userId, @Param("banks") Collection<String> banks,
            @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate, @Param("amounts") Collection<java.math.BigDecimal> amounts);

    /** Span and count of an account's transactions whose bank text contains a text (LOCATE, not LIKE: Hibernate adds an empty ESCAPE that Oracle reads as NULL). */
    interface SeenSpan {
        LocalDate getFirstDate();
        LocalDate getLastDate();
        long getTotal();
    }

    @Query("SELECT MIN(t.date) AS firstDate, MAX(t.date) AS lastDate, COUNT(t) AS total FROM Transaction t "
            + "WHERE t.user.id = :userId AND t.account.id = :accountId AND LOCATE(:text, t.sourcedDescription) > 0")
    SeenSpan findSeenSpan(@Param("userId") UUID userId, @Param("accountId") UUID accountId, @Param("text") String text);
}
