package com.financeos.domain.nps.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NpsLinkDismissalRepository extends JpaRepository<NpsLinkDismissal, UUID> {

    /** (transactionId, entryId or null) of every dismissal of the user, in one query. */
    @Query("SELECT d.transaction.id, d.entry.id FROM NpsLinkDismissal d WHERE d.user.id = :userId")
    List<Object[]> findPairsByUserId(@Param("userId") UUID userId);

    /** The entry-level dismissals of some entries, with their transactions and entries. */
    @Query("SELECT d FROM NpsLinkDismissal d JOIN FETCH d.transaction JOIN FETCH d.entry WHERE d.entry.id IN :ids")
    List<NpsLinkDismissal> findByEntryIds(@Param("ids") java.util.Collection<UUID> ids);

    @Query("SELECT COUNT(d) FROM NpsLinkDismissal d WHERE d.user.id = :userId AND d.transaction.id = :transactionId "
            + "AND d.entry.id = :entryId")
    long countPair(@Param("userId") UUID userId, @Param("transactionId") UUID transactionId,
                   @Param("entryId") UUID entryId);

    @Query("SELECT COUNT(d) FROM NpsLinkDismissal d WHERE d.user.id = :userId AND d.transaction.id = :transactionId "
            + "AND d.entry IS NULL")
    long countNotNps(@Param("userId") UUID userId, @Param("transactionId") UUID transactionId);
}
