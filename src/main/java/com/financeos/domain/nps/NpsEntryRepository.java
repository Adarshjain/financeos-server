package com.financeos.domain.nps;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NpsEntryRepository extends JpaRepository<NpsEntry, UUID> {

    /** All of a user's entries with their lines, in one query. */
    @Query("SELECT DISTINCT e FROM NpsEntry e JOIN FETCH e.account LEFT JOIN FETCH e.lines WHERE e.user.id = :userId")
    List<NpsEntry> findAllForUser(@Param("userId") UUID userId);

    @Query("SELECT e FROM NpsEntry e WHERE e.id = :id AND e.user.id = :userId")
    Optional<NpsEntry> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** The ids of one page of the user's entries (filters optional), newest posted date first. */
    @Query(value = "SELECT e.id FROM NpsEntry e WHERE e.user.id = :userId "
            + "AND (:accountId IS NULL OR e.account.id = :accountId) "
            + "AND (:tier IS NULL OR e.tier = :tier) AND (:type IS NULL OR e.type = :type) "
            + "AND (:schemeCode IS NULL OR EXISTS (SELECT 1 FROM NpsEntryLine l WHERE l.entry.id = e.id "
            + "AND l.schemeCode = :schemeCode)) "
            + "AND (:from IS NULL OR e.postedDate >= :from) AND (:to IS NULL OR e.postedDate <= :to) "
            + "AND (:contributor IS NULL OR e.contributor = :contributor) "
            + "ORDER BY e.postedDate DESC, e.createdAt DESC, e.id ASC",
            countQuery = "SELECT COUNT(e) FROM NpsEntry e WHERE e.user.id = :userId "
            + "AND (:accountId IS NULL OR e.account.id = :accountId) "
            + "AND (:tier IS NULL OR e.tier = :tier) AND (:type IS NULL OR e.type = :type) "
            + "AND (:schemeCode IS NULL OR EXISTS (SELECT 1 FROM NpsEntryLine l WHERE l.entry.id = e.id "
            + "AND l.schemeCode = :schemeCode)) "
            + "AND (:from IS NULL OR e.postedDate >= :from) AND (:to IS NULL OR e.postedDate <= :to) "
            + "AND (:contributor IS NULL OR e.contributor = :contributor)")
    Page<UUID> pageIds(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                       @Param("tier") NpsTier tier, @Param("type") NpsEntryType type,
                       @Param("schemeCode") String schemeCode, @Param("from") LocalDate from,
                       @Param("to") LocalDate to, @Param("contributor") NpsContributor contributor,
                       Pageable pageable);

    @Query("SELECT DISTINCT e FROM NpsEntry e LEFT JOIN FETCH e.lines JOIN FETCH e.account WHERE e.id IN :ids")
    List<NpsEntry> findWithLinesByIdIn(@Param("ids") Collection<UUID> ids);

    /** The earliest posted date of any entry with a line in one of the schemes, per scheme (scheme, date). */
    @Query("SELECT l.schemeCode, MIN(e.postedDate) FROM NpsEntry e JOIN e.lines l "
            + "WHERE l.schemeCode IN :codes GROUP BY l.schemeCode")
    List<Object[]> findEarliestPostedDates(@Param("codes") Collection<String> codes);

    /** The earliest posted date of any entry, per scheme held in any ledger (scheme, date). */
    @Query("SELECT l.schemeCode, MIN(e.postedDate) FROM NpsEntry e JOIN e.lines l GROUP BY l.schemeCode")
    List<Object[]> findEarliestPostedDatesForAllLines();

    /** One account's entries of one tier with their lines, in one query. */
    @Query("SELECT DISTINCT e FROM NpsEntry e LEFT JOIN FETCH e.lines "
            + "WHERE e.user.id = :userId AND e.account.id = :accountId AND e.tier = :tier")
    List<NpsEntry> findForAccountTier(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                      @Param("tier") NpsTier tier);

    /** The entries a statement created, with their lines. */
    @Query("SELECT DISTINCT e FROM NpsEntry e LEFT JOIN FETCH e.lines "
            + "WHERE e.user.id = :userId AND e.statementId = :statementId")
    List<NpsEntry> findByStatementId(@Param("userId") UUID userId, @Param("statementId") UUID statementId);

    /** The user's own contributions that no payment is linked to yet, with their accounts, in one query. */
    @Query("SELECT e FROM NpsEntry e JOIN FETCH e.account WHERE e.user.id = :userId "
            + "AND e.type = com.financeos.domain.nps.NpsEntryType.CONTRIBUTION "
            + "AND e.contributor = com.financeos.domain.nps.NpsContributor.SELF "
            + "AND e.transaction IS NULL AND e.amount > 0")
    List<NpsEntry> findUnlinkedSelfContributions(@Param("userId") UUID userId);

    /** Reverse lookup for transaction responses (batch, one query). */
    @Query("SELECT e FROM NpsEntry e JOIN FETCH e.account WHERE e.transaction.id IN :ids")
    List<NpsEntry> findWithAccountByTransactionIdIn(@Param("ids") Collection<UUID> ids);

    /** ESTIMATED entries with a line in one of the schemes, with all their lines, in one query (any user). */
    @Query("SELECT DISTINCT e FROM NpsEntry e LEFT JOIN FETCH e.lines "
            + "WHERE e.status = com.financeos.domain.nps.NpsEntryStatus.ESTIMATED "
            + "AND e.id IN (SELECT l.entry.id FROM NpsEntryLine l WHERE l.schemeCode IN :codes)")
    List<NpsEntry> findEstimatedWithSchemes(@Param("codes") Collection<String> codes);
}
