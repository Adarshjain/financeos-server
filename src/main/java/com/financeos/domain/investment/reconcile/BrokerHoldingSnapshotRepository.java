package com.financeos.domain.investment.reconcile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Every read names the owner: the worker runs without the request filter. */
@Repository
public interface BrokerHoldingSnapshotRepository extends JpaRepository<BrokerHoldingSnapshot, UUID> {

    /** All of {@code userId}'s snapshot rows (one snapshot per broker account is kept). */
    @Query("SELECT s FROM BrokerHoldingSnapshot s WHERE s.user.id = :userId")
    List<BrokerHoldingSnapshot> findOwned(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM BrokerHoldingSnapshot s WHERE s.user.id = :userId AND s.brokerAccountId = :accountId")
    void deleteForAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId);
}
