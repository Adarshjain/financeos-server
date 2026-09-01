package com.financeos.domain.account.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT ch FROM Cardholder ch WHERE ch.account.id = :accountId AND ch.closedOn IS NULL AND (ch.account.closedOn IS NULL OR ch.account.closedOn > CURRENT_DATE)")
    List<Cardholder> findOpenByAccountId(@Param("accountId") UUID accountId);
}
