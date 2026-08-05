package com.financeos.domain.investment.fno;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FnoTradeRepository extends JpaRepository<FnoTrade, UUID> {

    List<FnoTrade> findByBrokerAccountId(UUID brokerAccountId);

    @Query("SELECT f FROM FnoTrade f LEFT JOIN FETCH f.brokerAccount ORDER BY f.createdAt DESC")
    List<FnoTrade> findAllWithBrokerAccount();

    @Query("SELECT COALESCE(SUM(f.realizedPnl), 0) FROM FnoTrade f")
    BigDecimal sumRealizedPnl();

    boolean existsByBrokerAccountIdAndExternalRef(UUID brokerAccountId, String externalRef);

    boolean existsByBrokerAccountIdAndTradingSymbolAndEntryDateAndExitDateAndQuantityAndBuyValueAndSellValue(
            UUID brokerAccountId,
            String tradingSymbol,
            LocalDate entryDate,
            LocalDate exitDate,
            BigDecimal quantity,
            BigDecimal buyValue,
            BigDecimal sellValue
    );
}
