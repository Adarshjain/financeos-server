package com.financeos.domain.instrument.corporateaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CorporateActionRepository extends JpaRepository<CorporateAction, UUID> {

    List<CorporateAction> findByInstrumentIdOrderByExDateAsc(UUID instrumentId);

    List<CorporateAction> findByTargetInstrumentIdOrderByExDateAsc(UUID targetInstrumentId);

    List<CorporateAction> findAllByOrderByExDateDesc();

    @Query("SELECT ca FROM CorporateAction ca LEFT JOIN FETCH ca.instrument LEFT JOIN FETCH ca.targetInstrument ORDER BY ca.exDate DESC")
    List<CorporateAction> findAllWithInstruments();
}
