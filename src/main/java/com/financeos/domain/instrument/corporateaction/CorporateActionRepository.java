package com.financeos.domain.instrument.corporateaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Corporate actions are per user, so every read here names the owner: the lot engine runs for a
 * holding's owner, and jobs run without a signed-in user (the Hibernate {@code userFilter} is then
 * off), so an owner-less read would mix users. A null owner matches nothing: every read is an
 * explicit JPQL equality on the owner (a derived query would turn a null owner into {@code IS NULL}
 * and return owner-less rows).
 */
@Repository
public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    /** One of {@code userId}'s corporate actions (another user's id is empty). */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.id = :id AND ca.user.id = :userId")
    Optional<CorporateAction> findByIdAndUser_Id(@Param("id") UUID id, @Param("userId") UUID userId);

    /** {@code userId}'s corporate actions on one instrument, oldest first. */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.user.id = :userId AND ca.instrument.id = :instrumentId "
            + "ORDER BY ca.exDate ASC")
    List<CorporateAction> findByUser_IdAndInstrument_IdOrderByExDateAsc(@Param("userId") UUID userId,
                                                                      @Param("instrumentId") UUID instrumentId);

    /** {@code userId}'s demergers/mergers into one instrument, oldest first. */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.user.id = :userId AND ca.targetInstrument.id = :targetId "
            + "ORDER BY ca.exDate ASC")
    List<CorporateAction> findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(@Param("userId") UUID userId,
                                                                            @Param("targetId") UUID targetInstrumentId);

    /** {@code userId}'s corporate actions on several instruments, oldest first, instruments fetched. */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.user.id = :userId AND ca.instrument.id IN :ids ORDER BY ca.exDate ASC")
    List<CorporateAction> findOwnedByInstrumentIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /** {@code userId}'s demergers/mergers that seed shares into several instruments, oldest first, instruments fetched. */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.user.id = :userId AND ca.targetInstrument.id IN :ids ORDER BY ca.exDate ASC")
    List<CorporateAction> findOwnedByTargetInstrumentIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /**
     * The corporate actions of {@code userIds} on several instruments with an ex-date on or after
     * {@code from}, owners and instruments fetched (callers key them by owner and instrument).
     */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.user JOIN FETCH ca.instrument "
            + "WHERE ca.user.id IN :userIds AND ca.instrument.id IN :ids AND ca.exDate >= :from ORDER BY ca.exDate ASC")
    List<CorporateAction> findOwnedByInstrumentIdsExDateFrom(@Param("userIds") Collection<UUID> userIds,
                                                             @Param("ids") Collection<UUID> ids,
                                                             @Param("from") LocalDate from);

    /**
     * Every corporate action of {@code userId} that {@code ids} take part in — as the instrument it
     * applies to (split, bonus, demerger parent, merger transferor) or as its target (demerger child,
     * merger acquirer) — oldest first, instruments fetched.
     */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.user.id = :userId AND (ca.instrument.id IN :ids OR ca.targetInstrument.id IN :ids) "
            + "ORDER BY ca.exDate ASC")
    List<CorporateAction> findOwnedInvolving(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /** All of {@code userId}'s corporate actions, newest first, instruments fetched. */
    @Query("SELECT ca FROM CorporateAction ca LEFT JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.user.id = :userId ORDER BY ca.exDate DESC, ca.id ASC")
    List<CorporateAction> findAllOwnedWithInstruments(@Param("userId") UUID userId);
}
