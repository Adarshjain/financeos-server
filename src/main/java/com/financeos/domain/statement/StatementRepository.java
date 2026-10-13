package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    boolean existsByAccountIdAndPeriodStartAndPeriodEnd(UUID accountId, LocalDate periodStart, LocalDate periodEnd);

    boolean existsByAccountIdAndFileSha256(UUID accountId, String fileSha256);

    /** The statement only when it belongs to this account — the caller has already proven the account is theirs. */
    Optional<Statement> findByIdAndAccount_Id(UUID id, UUID accountId);

    @Query("SELECT MAX(s.periodEnd) FROM Statement s WHERE s.account.id = :accountId")
    LocalDate findLatestPeriodEnd(@Param("accountId") UUID accountId);

    @Query("SELECT s FROM Statement s WHERE s.account.id = :accountId ORDER BY s.periodEnd DESC NULLS LAST, s.createdAt DESC")
    List<Statement> findByAccountIdOrderByPeriodEndDescNullsLast(@Param("accountId") UUID accountId);

    List<Statement> findByAccountIdOrderByPeriodEndAsc(UUID accountId);

    /** Statements whose review digest has not run yet, ingested inside [from, to] (old enough to be fully linked, new enough to matter). */
    @Query("SELECT s FROM Statement s WHERE s.user.id = :userId AND s.reviewNotifiedOn IS NULL AND s.createdAt BETWEEN :from AND :to AND (s.verdict IS NULL OR s.verdict <> com.financeos.domain.statement.StatementVerdict.REJECTED) ORDER BY s.createdAt ASC")
    List<Statement> findReviewDigestCandidates(@Param("userId") UUID userId, @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    interface AnchorStatementProjection {
        UUID getId();
        LocalDate getPeriodEnd();
        java.math.BigDecimal getClosingBalance();
    }

    @Query("SELECT s FROM Statement s JOIN FETCH s.creditCardDetails d WHERE s.account.id = :accountId AND s.statementType = 'credit_card' AND s.verdict != com.financeos.domain.statement.StatementVerdict.REJECTED ORDER BY s.periodEnd ASC NULLS LAST, s.createdAt ASC")
    List<Statement> findQualifyingCreditCardStatements(@Param("accountId") UUID accountId);

    /**
     * The statements an account balance can anchor on, latest first: dated, not rejected, with a
     * closing balance — the parser's, else (credit cards) the total amount due, the same figure:
     * what the card owed when the statement closed. A card statement the parser could not
     * reconcile carries no closing balance yet still prints the total due, and the bill
     * ({@code CardBillService}) already treats it as the current statement; without the fallback
     * the balance would stay anchored on the previous statement and disagree with the bill. The
     * batch query ({@code AccountRepositoryCustomImpl.findAccountBalanceBatches}) applies the same rule.
     */
    @Query("SELECT s.id AS id, s.periodEnd AS periodEnd, COALESCE(s.closingBalance, d.totalAmountDue) AS closingBalance "
           + "FROM Statement s LEFT JOIN s.creditCardDetails d "
           + "WHERE s.account.id = :accountId AND s.periodEnd IS NOT NULL "
           + "AND COALESCE(s.closingBalance, d.totalAmountDue) IS NOT NULL "
           + "AND s.verdict != com.financeos.domain.statement.StatementVerdict.REJECTED "
           + "ORDER BY s.periodEnd DESC, s.createdAt DESC")
    List<AnchorStatementProjection> findEligibleAnchorStatements(@Param("accountId") UUID accountId, org.springframework.data.domain.Pageable pageable);

    /**
     * The latest transaction date among the statement's linked lines, null when it has none: with
     * the period end, the last day the statement bills (see
     * {@link com.financeos.domain.account.BalanceMath#cutoff}).
     */
    @Query("SELECT MAX(t.date) FROM StatementTransaction st, Transaction t WHERE st.id.statementId = :statementId AND t.id = st.id.transactionId")
    LocalDate findLastLineDate(@Param("statementId") UUID statementId);

    /**
     * Credit limits printed on the account's non-rejected statements, newest first (pass a one-row
     * page for the latest): the utilisation fallback for a card without a limit of its own.
     */
    @Query("SELECT d.creditLimit FROM StatementCreditCardDetails d JOIN d.statement s " +
           "WHERE s.account.id = :accountId AND d.creditLimit IS NOT NULL " +
           "AND (s.verdict IS NULL OR s.verdict <> com.financeos.domain.statement.StatementVerdict.REJECTED) " +
           "ORDER BY s.periodEnd DESC NULLS LAST, s.createdAt DESC")
    List<java.math.BigDecimal> findLatestCreditLimits(@Param("accountId") UUID accountId,
                                                       org.springframework.data.domain.Pageable pageable);
}
