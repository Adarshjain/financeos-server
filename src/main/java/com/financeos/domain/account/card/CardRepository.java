package com.financeos.domain.account.card;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByCardholderId(UUID cardholderId);

    /**
     * Card with its cardholder initialized — for entities handed to response mapping
     * outside the persistence session (cardholder is LAZY on Card).
     */
    @EntityGraph(attributePaths = { "cardholder" })
    @Query("SELECT c FROM Card c WHERE c.id = :id")
    Optional<Card> findWithCardholderById(@Param("id") UUID id);

    List<Card> findByAccountId(UUID accountId);

    @Query("SELECT c FROM Card c WHERE c.cardholder.id = :cardholderId AND c.closedOn IS NULL")
    Optional<Card> findOpenByCardholderId(@Param("cardholderId") UUID cardholderId);

    @Query("SELECT c FROM Card c WHERE c.account.id = :accountId AND c.last4 = :last4 AND c.closedOn IS NULL")
    Optional<Card> findOpenByAccountIdAndLast4(@Param("accountId") UUID accountId, @Param("last4") String last4);

    @Query("SELECT c FROM Card c WHERE c.user.id = :userId AND c.last4 = :last4")
    List<Card> findByUserIdAndLast4(@Param("userId") UUID userId, @Param("last4") String last4);

    @Query("SELECT c FROM Card c WHERE c.user.id = :userId AND c.closedOn IS NULL")
    List<Card> findAllOpenByUserId(@Param("userId") UUID userId);

    @Query("SELECT c FROM Card c WHERE c.account.id = :accountId AND c.closedOn IS NULL")
    List<Card> findOpenByAccountId(@Param("accountId") UUID accountId);
}
