package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SchemeEvidenceChildRepository extends JpaRepository<SchemeEvidenceChild, UUID> {

    /** The children of several evidence rows, in their order. */
    @Query("SELECT c FROM SchemeEvidenceChild c WHERE c.evidenceId IN :ids ORDER BY c.evidenceId, c.childOrder")
    List<SchemeEvidenceChild> findForEvidence(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("DELETE FROM SchemeEvidenceChild c WHERE c.evidenceId = :id")
    void deleteForEvidence(@Param("id") UUID id);
}
