package com.financeos.domain.nps;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface NpsSchemeRepository extends JpaRepository<NpsScheme, String> {

    List<NpsScheme> findByInstrumentIdIn(Collection<UUID> instrumentIds);

    /**
     * The catalog filtered for pickers: text matches the code and the scheme / fund-house name
     * (LOCATE, not LIKE, so a typed % or _ is no wildcard; {@code search} is lowercased by the caller).
     */
    @Query("SELECT s FROM NpsScheme s, Instrument i WHERE i.id = s.instrumentId AND "
            + "(:search IS NULL OR LOCATE(:search, LOWER(i.name)) > 0 OR LOCATE(:search, LOWER(s.schemeCode)) > 0 "
            + "OR LOCATE(:search, LOWER(s.pfmName)) > 0) AND "
            + "(:tier IS NULL OR s.tier = :tier) AND (:variant IS NULL OR s.variant = :variant) AND "
            + "(:pfm IS NULL OR s.pfmCode = :pfm) ORDER BY LOWER(s.pfmName), s.schemeCode")
    List<NpsScheme> search(@Param("search") String search, @Param("tier") NpsSchemeTier tier,
                           @Param("variant") NpsVariant variant, @Param("pfm") String pfm);
}
