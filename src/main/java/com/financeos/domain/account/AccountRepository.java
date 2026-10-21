package com.financeos.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID>, AccountRepositoryCustom {

    List<Account> findByType(AccountType type);

    List<Account> findByFinancialPosition(FinancialPosition position);

    @Query("SELECT a FROM Account a WHERE a.excludeFromNetAsset = false")
    List<Account> findIncludedInNetAsset();

    @Query("SELECT a FROM Account a WHERE a.type = 'broker'")
    List<Account> findInvestmentAccounts();

    @Query("SELECT a FROM Account a LEFT JOIN a.bankDetails b LEFT JOIN a.creditCardDetails c WHERE a.user.id = :userId AND (b.statementPassword IS NOT NULL OR c.statementPassword IS NOT NULL)")
    List<Account> findByUserIdAndHasStatementPassword(UUID userId);

    List<Account> findByUserId(UUID userId);

    /** The account when {@code userId} owns it; empty for a missing id and for another user's account alike. */
    Optional<Account> findByIdAndUser_Id(UUID id, UUID userId);

    /** Detail rows fetched with the accounts: they are inverse one-to-ones Hibernate would otherwise load one by one. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"bankDetails", "creditCardDetails", "brokerDetails"})
    List<Account> findByUserIdAndType(UUID userId, AccountType type);

    /** Ids of every user owning at least one account of {@code type} (for system-wide ticks; no user context). */
    @Query("SELECT DISTINCT a.user.id FROM Account a WHERE a.type = :type")
    List<UUID> findDistinctUserIdsByType(@Param("type") AccountType type);

    /** Whether the user has at least one account of {@code type} (open or closed). */
    boolean existsByUser_IdAndType(UUID userId, AccountType type);

    /** Whether the user has any account at all. */
    boolean existsByUser_Id(UUID userId);

    /** Every account (open or closed) of {@code userId} that draws on the shared limit {@code poolId}. */
    @Query("SELECT d.account FROM AccountCreditCardDetails d WHERE d.limitPool.id = :poolId AND d.account.user.id = :userId")
    List<Account> findLimitPoolMembers(@Param("poolId") UUID poolId, @Param("userId") UUID userId);

    /** The shared limit when {@code userId} owns it; empty for a missing id and another user's pool alike. */
    @Query("SELECT p FROM CreditLimitPool p WHERE p.id = :id AND p.user.id = :userId")
    Optional<CreditLimitPool> findOwnedLimitPool(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Deletes the user's shared limits that no account draws on any more (pending changes are flushed first). */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CreditLimitPool p WHERE p.user.id = :userId "
            + "AND NOT EXISTS (SELECT d FROM AccountCreditCardDetails d WHERE d.limitPool = p)")
    int deleteEmptyLimitPools(@Param("userId") UUID userId);
}

