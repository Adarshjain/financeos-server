package com.financeos.domain.nps;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NpsStatementRepository extends JpaRepository<NpsStatement, UUID> {

    /** The user's statements (optionally of one account), without their holding rows. */
    @Query("SELECT s FROM NpsStatement s JOIN FETCH s.account "
            + "WHERE s.user.id = :userId AND (:accountId IS NULL OR s.account.id = :accountId)")
    List<NpsStatement> findForUser(@Param("userId") UUID userId, @Param("accountId") UUID accountId);

    @Query("SELECT DISTINCT s FROM NpsStatement s JOIN FETCH s.account LEFT JOIN FETCH s.holdings "
            + "WHERE s.id = :id AND s.user.id = :userId")
    Optional<NpsStatement> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** One account's statements of one tier with their holding rows, oldest period first. */
    @Query("SELECT DISTINCT s FROM NpsStatement s LEFT JOIN FETCH s.holdings "
            + "WHERE s.user.id = :userId AND s.account.id = :accountId AND s.tier = :tier "
            + "ORDER BY s.periodStart, s.periodEnd, s.id")
    List<NpsStatement> findForAccountTier(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                          @Param("tier") NpsTier tier);

    /** The statements without their holdings, for the summary flag. */
    @Query("SELECT s FROM NpsStatement s WHERE s.user.id = :userId")
    List<NpsStatement> findAllLightForUser(@Param("userId") UUID userId);

    /** All of one account's statements with their holding rows (to delete them with the account). */
    @Query("SELECT DISTINCT s FROM NpsStatement s LEFT JOIN FETCH s.holdings "
            + "WHERE s.user.id = :userId AND s.account.id = :accountId")
    List<NpsStatement> findAllForAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);
}
