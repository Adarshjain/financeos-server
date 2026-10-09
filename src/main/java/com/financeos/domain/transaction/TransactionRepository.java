package com.financeos.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, TransactionRepositoryCustom {

    interface TransactionBalanceProjection {
        UUID getId();

        java.math.BigDecimal getBalance();
    }

    @Override
    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findAll(Pageable pageable);


    @EntityGraph(attributePaths = { "categories.category", "account", "card", "card.cardholder", "reviewReasons" })
    List<Transaction> findAllByIdIn(List<UUID> ids);

    @EntityGraph(attributePaths = { "categories.category", "account", "card", "card.cardholder", "reviewReasons" })
    List<Transaction> findAllByIdInAndUserId(List<UUID> ids, UUID userId);

    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByDateRange(@Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT t FROM Transaction t JOIN t.categories tc JOIN tc.category c WHERE c.name = :category ORDER BY t.date DESC")
    List<Transaction> findByCategory(@Param("category") String category);

    boolean existsBySourceMessageId(String sourceMessageId);

    boolean existsByUserIdAndSourceMessageId(UUID userId, String sourceMessageId);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.source = :source AND t.reviewType = :reviewType")
    List<Transaction> findByAccountIdAndSourceAndReviewType(
            @Param("accountId") UUID accountId,
            @Param("source") TransactionSource source,
            @Param("reviewType") ReviewType reviewType);

    /** Card bills: credits posted after a statement's period end (payments, refunds, cashback — the caller sorts them out). */
    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId " +
           "AND t.type = com.financeos.domain.transaction.TransactionType.CREDIT AND t.date > :afterDate " +
           "ORDER BY t.date DESC, t.createdAt DESC")
    List<Transaction> findCreditsAfter(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    /** Reward engine: fetch by effective date (settlement date when present, else transaction date). */
    @EntityGraph(attributePaths = { "categories.category", "card.cardholder" })
    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId " +
            "AND COALESCE(t.settlementDate, t.date) BETWEEN :startDate AND :endDate")
    List<Transaction> findForRewardEvaluation(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<Transaction> findByAppliedRuleId(UUID appliedRuleId);

    List<Transaction> findByAppliedRuleIdIn(Collection<UUID> appliedRuleIds);

    interface RuleMatchCandidate {
        UUID getId();

        String getSourcedDescription();
    }

    /**
     * Candidates for category-rule matching: only ingested transactions (rules never match
     * manual descriptions) that aren't manually reviewed. Lightweight projection because the
     * rule predicate runs in Java — MERCHANT_KEY normalization and REGEX can't be pushed to SQL.
     */
    @Query("SELECT t.id AS id, t.sourcedDescription AS sourcedDescription FROM Transaction t " +
           "WHERE t.user.id = :userId AND t.sourcedDescription IS NOT NULL " +
           "AND (t.reviewType IS NULL OR t.reviewType <> :excludedReviewType) " +
           "ORDER BY t.date DESC, t.createdAt DESC")
    List<RuleMatchCandidate> findRuleMatchCandidates(
            @Param("userId") UUID userId,
            @Param("excludedReviewType") ReviewType excludedReviewType);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.account.id = :accountId")
    java.math.BigDecimal findTotalTransactionSumByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(CASE WHEN t.type = com.financeos.domain.transaction.TransactionType.CREDIT THEN t.amount ELSE -t.amount END), 0) FROM Transaction t WHERE t.account.id = :accountId AND t.date > :afterDate")
    java.math.BigDecimal findPostAnchorTransactionSumByAccountId(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    /** Card unbilled spend: non-excluded DEBITs posted after {@code afterDate} (a statement's period end). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId " +
           "AND t.type = com.financeos.domain.transaction.TransactionType.DEBIT AND t.isTransactionExcluded = false " +
           "AND t.date > :afterDate")
    java.math.BigDecimal sumIncludedDebitsAfter(@Param("accountId") UUID accountId, @Param("afterDate") LocalDate afterDate);

    /** Card unbilled spend with no statement yet: every non-excluded DEBIT. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.account.id = :accountId " +
           "AND t.type = com.financeos.domain.transaction.TransactionType.DEBIT AND t.isTransactionExcluded = false")
    java.math.BigDecimal sumIncludedDebits(@Param("accountId") UUID accountId);

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
     * {@code date > afterDate} when the balance is anchored on a statement ending {@code afterDate};
     * a null {@code afterDate} (no anchor) lists them all.
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

    /** One category of one transaction, by name. */
    interface BalanceTransactionCategoryRow {
        UUID getTransactionId();

        String getName();
    }

    /** The category names of every transaction {@link #findBalanceTransactionRows} lists. */
    @Query("SELECT tc.transaction.id AS transactionId, c.name AS name FROM TransactionCategory tc JOIN tc.category c " +
           "WHERE tc.transaction.account.id = :accountId " +
           "AND (:afterDate IS NULL OR tc.transaction.date > :afterDate)")
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

    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.amount BETWEEN :minAmount AND :maxAmount AND t.date BETWEEN :minDate AND :maxDate AND t.account.id = :accountId")
    List<Transaction> findMatchCandidatesByAccount(
            @Param("type") TransactionType type,
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
            @Param("minDate") LocalDate minDate,
            @Param("maxDate") LocalDate maxDate,
            @Param("accountId") UUID accountId);

    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.amount BETWEEN :minAmount AND :maxAmount AND t.date BETWEEN :minDate AND :maxDate")
    List<Transaction> findMatchCandidates(
            @Param("type") TransactionType type,
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
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

    @Query("SELECT t FROM Transaction t JOIN t.reviewReasons r WHERE t.account.id = :accountId AND t.user.id = :userId AND t.source = com.financeos.domain.transaction.TransactionSource.gmail_transaction_alert AND t.date < :beforeDate AND r = com.financeos.domain.transaction.ReviewReason.UNRECONCILED")
    List<Transaction> findUnreconciledAlertsBeforeDate(
            @Param("accountId") UUID accountId,
            @Param("userId") UUID userId,
            @Param("beforeDate") LocalDate beforeDate);

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

    /** Inbox: how many of the user's transactions sit in the review queue. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.user.id = :userId AND t.reviewType = :reviewType")
    long countByUserIdAndReviewType(@Param("userId") UUID userId, @Param("reviewType") ReviewType reviewType);

    /** Inbox: of those, how many carry one review reason (e.g. CATEGORY_UNVERIFIED). */
    @Query("SELECT COUNT(DISTINCT t.id) FROM Transaction t JOIN t.reviewReasons r WHERE t.user.id = :userId AND t.reviewType = :reviewType AND r = :reason")
    long countByUserIdAndReviewTypeAndReason(@Param("userId") UUID userId, @Param("reviewType") ReviewType reviewType,
                                             @Param("reason") ReviewReason reason);
}
