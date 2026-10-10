package com.financeos.domain.instrument.corporateaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    List<CorporateAction> findByInstrumentIdOrderByExDateAsc(UUID instrumentId);

    List<CorporateAction> findByTargetInstrumentIdOrderByExDateAsc(UUID targetInstrumentId);

    List<CorporateAction> findAllByOrderByExDateDesc();

    /** The corporate actions of several instruments, oldest first, instruments fetched. */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.instrument.id IN :ids ORDER BY ca.exDate ASC")
    List<CorporateAction> findByInstrumentIdsWithInstruments(@Param("ids") Collection<UUID> ids);

    /** The demergers/mergers that seed shares into several instruments, oldest first, instruments fetched. */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.targetInstrument.id IN :ids ORDER BY ca.exDate ASC")
    List<CorporateAction> findByTargetInstrumentIdsWithInstruments(@Param("ids") Collection<UUID> ids);

    /** The corporate actions of several instruments with an ex-date on or after {@code from}. */
    @Query("SELECT ca FROM CorporateAction ca WHERE ca.instrument.id IN :ids AND ca.exDate >= :from ORDER BY ca.exDate ASC")
    List<CorporateAction> findByInstrumentIdsExDateFrom(@Param("ids") Collection<UUID> ids, @Param("from") LocalDate from);

    /**
     * Every corporate action any of {@code ids} takes part in — as the instrument it applies to (split,
     * bonus, demerger parent, merger transferor) or as its target (demerger child, merger acquirer) —
     * oldest first, instruments fetched.
     */
    @Query("SELECT ca FROM CorporateAction ca JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument "
            + "WHERE ca.instrument.id IN :ids OR ca.targetInstrument.id IN :ids ORDER BY ca.exDate ASC")
    List<CorporateAction> findInvolving(@Param("ids") Collection<UUID> ids);

    @Query("SELECT ca FROM CorporateAction ca LEFT JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument ORDER BY ca.exDate DESC")
    List<CorporateAction> findAllWithInstruments();
}
