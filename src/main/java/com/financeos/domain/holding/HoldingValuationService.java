package com.financeos.domain.holding;

import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class HoldingValuationService {

    private final HoldingRepository holdingRepository;
    private final InvestmentService investmentService;

    public HoldingValuationService(HoldingRepository holdingRepository,
                                  InvestmentService investmentService) {
        this.holdingRepository = holdingRepository;
        this.investmentService = investmentService;
    }

    public BigDecimal getBrokerMarketValue(UUID brokerAccountId) {
        List<Holding> holdings = holdingRepository.findByBrokerAccountId(brokerAccountId);
        BigDecimal totalMarketValue = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            HoldingPosition position = investmentService.calculateHoldingPosition(holding);
            if (position != null && position.currentValue() != null) {
                totalMarketValue = totalMarketValue.add(position.currentValue());
            }
        }

        return totalMarketValue;
    }
}
