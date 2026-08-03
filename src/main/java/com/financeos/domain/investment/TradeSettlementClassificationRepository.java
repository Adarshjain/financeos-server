package com.financeos.domain.investment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TradeSettlementClassificationRepository extends JpaRepository<TradeSettlementClassification, UUID> {
    List<TradeSettlementClassification> findByHoldingId(UUID holdingId);
    List<TradeSettlementClassification> findByBrokerAccountIdAndInstrumentId(UUID brokerAccountId, UUID instrumentId);
    Optional<TradeSettlementClassification> findByBrokerAccountIdAndInstrumentIdAndTradeDate(UUID brokerAccountId, UUID instrumentId, LocalDate tradeDate);
    void deleteByBrokerAccountId(UUID brokerAccountId);
}
