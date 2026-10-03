package com.financeos.domain.account.card;

import com.financeos.core.time.AppTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardholderRepository extends JpaRepository<Cardholder, UUID> {

    List<Cardholder> findByAccountId(UUID accountId);

    @Query("SELECT ch FROM Cardholder ch WHERE ch.account.id = :accountId AND ch.role = 'PRIMARY'")
    Optional<Cardholder> findPrimaryByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT ch FROM Cardholder ch WHERE ch.id = :id AND ch.account.id = :accountId")
    Optional<Cardholder> findByIdAndAccountId(@Param("id") UUID id, @Param("accountId") UUID accountId);

    long countByAccountId(UUID accountId);

    /** Open cardholders of an account that is itself open on {@code today} (a business date, not the DB's clock). */
    @Query("SELECT ch FROM Cardholder ch WHERE ch.account.id = :accountId AND ch.closedOn IS NULL AND (ch.account.closedOn IS NULL OR ch.account.closedOn > :today)")
    List<Cardholder> findOpenByAccountIdAsOf(@Param("accountId") UUID accountId, @Param("today") LocalDate today);

    default List<Cardholder> findOpenByAccountId(UUID accountId) {
        return findOpenByAccountIdAsOf(accountId, AppTime.today());
    }

    @Query("SELECT DISTINCT ch FROM Cardholder ch LEFT JOIN FETCH ch.cards WHERE ch.account.id IN :accountIds ORDER BY ch.role ASC, ch.createdAt ASC")
    List<Cardholder> findByAccountIdInWithCards(@Param("accountIds") List<UUID> accountIds);
}
