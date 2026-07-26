package com.financeos.domain.holding;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class HoldingValuationService {

    private final HoldingRepository holdingRepository;
    private final InvestmentTransactionRepository transactionRepository;
    private final InstrumentPriceRepository priceRepository;

    public HoldingValuationService(HoldingRepository holdingRepository,
                                  InvestmentTransactionRepository transactionRepository,
                                  InstrumentPriceRepository priceRepository) {
        this.holdingRepository = holdingRepository;
        this.transactionRepository = transactionRepository;
        this.priceRepository = priceRepository;
    }

    public BigDecimal getBrokerMarketValue(UUID brokerAccountId) {
        List<Holding> holdings = holdingRepository.findByBrokerAccountId(brokerAccountId);
        BigDecimal totalMarketValue = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            List<InvestmentTransaction> txns = transactionRepository
                    .findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId());
            BigDecimal openQty = BigDecimal.ZERO;
            for (InvestmentTransaction txn : txns) {
                if (txn.getType() == InvestmentTransactionType.buy) {
                    openQty = openQty.add(txn.getQuantity());
                } else if (txn.getType() == InvestmentTransactionType.sell) {
                    openQty = openQty.subtract(txn.getQuantity());
                }
            }

            if (openQty.compareTo(BigDecimal.ZERO) > 0) {
                Optional<InstrumentPrice> latestPrice = priceRepository
                        .findTopByInstrumentIdOrderByAsOfDesc(holding.getInstrument().getId());
                if (latestPrice.isPresent() && latestPrice.get().getClose() != null) {
                    BigDecimal holdingValue = openQty.multiply(latestPrice.get().getClose());
                    totalMarketValue = totalMarketValue.add(holdingValue);
                }
            }
        }

        return totalMarketValue;
    }
}
