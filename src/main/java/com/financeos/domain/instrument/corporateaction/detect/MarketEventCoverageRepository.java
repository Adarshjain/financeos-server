package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketEventCoverageRepository extends JpaRepository<MarketEventCoverage, String> {
}
