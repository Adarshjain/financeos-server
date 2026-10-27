package com.financeos.domain.epf;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EpfLinkDismissalRepository extends JpaRepository<EpfLinkDismissal, UUID> {

    /** Entry id and transaction id of every dismissal of the user (ids only: no entity loading). */
    @Query("SELECT d.entry.id, d.transaction.id FROM EpfLinkDismissal d WHERE d.user.id = :userId")
    List<Object[]> findPairsForUser(@Param("userId") UUID userId);

    boolean existsByEntry_IdAndTransaction_Id(UUID entryId, UUID transactionId);
}
