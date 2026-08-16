package com.financeos.domain.cardfee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardFeeChargeRepository extends JpaRepository<CardFeeCharge, UUID> {

    Optional<CardFeeCharge> findByAccountIdAndKindAndFeeYearStart(UUID accountId, CardFeeKind kind, LocalDate feeYearStart);

    List<CardFeeCharge> findByAccountId(UUID accountId);

    @Query("SELECT ct FROM CardFeeCharge c JOIN c.transactionIds ct WHERE c.account.id = :accountId")
    List<UUID> findAllLinkedTransactionIdsByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT c FROM CardFeeCharge c JOIN c.transactionIds ct WHERE ct = :transactionId")
    Optional<CardFeeCharge> findByTransactionId(@Param("transactionId") UUID transactionId);
}
