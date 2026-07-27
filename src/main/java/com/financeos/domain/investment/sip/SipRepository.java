package com.financeos.domain.investment.sip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SipRepository extends JpaRepository<Sip, UUID> {

    @Query("""
        SELECT s FROM Sip s
        WHERE (:brokerAccountId IS NULL OR s.brokerAccount.id = :brokerAccountId)
          AND (:instrumentId IS NULL OR s.instrument.id = :instrumentId)
          AND (:active IS NULL OR s.active = :active)
    """)
    List<Sip> findFilteredSips(
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("active") Boolean active
    );
}
