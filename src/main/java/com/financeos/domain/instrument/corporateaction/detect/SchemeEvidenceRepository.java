package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Global evidence rows: no user is involved, so reads need no owner. */
@Repository
public interface SchemeEvidenceRepository extends JpaRepository<SchemeEvidence, UUID> {

    /** The rows of one kind for any of the (upper-case) parent symbols (one query for a whole page). */
    @Query("SELECT e FROM SchemeEvidence e WHERE e.kind = :kind AND e.parentSymbol IN :symbols")
    List<SchemeEvidence> findByKindAndSymbols(@Param("kind") String kind, @Param("symbols") Collection<String> symbols);

    /** One scheme's row; a null {@code exDate} finds the merger row (which has none). */
    @Query("SELECT e FROM SchemeEvidence e WHERE e.parentSymbol = :symbol AND e.kind = :kind "
            + "AND ((:exDate IS NULL AND e.exDate IS NULL) OR e.exDate = :exDate)")
    Optional<SchemeEvidence> findScheme(@Param("symbol") String symbol, @Param("kind") String kind, @Param("exDate") LocalDate exDate);
}
