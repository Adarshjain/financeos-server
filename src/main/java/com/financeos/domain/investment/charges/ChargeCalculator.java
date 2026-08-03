package com.financeos.domain.investment.charges;

import com.financeos.api.investment.dto.ItemizedChargesDto;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Component
public class ChargeCalculator {

    public ItemizedChargesDto calculateGrowwCharges(
            InvestmentTransactionType side,
            SettlementType settlementType,
            BigDecimal quantity,
            BigDecimal price,
            LocalDate tradeDate,
            String exchange
    ) {
        if (quantity == null || price == null || quantity.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
            return ItemizedChargesDto.empty();
        }

        BigDecimal tradeValue = quantity.multiply(price);
        boolean isBuy = side == InvestmentTransactionType.buy;
        boolean isDelivery = settlementType == null || settlementType == SettlementType.delivery;

        // 1. Brokerage
        BigDecimal brokeragePct = new BigDecimal("0.001"); // 0.1%
        BigDecimal rawBrokerage = tradeValue.multiply(brokeragePct);
        BigDecimal maxBrokerage = new BigDecimal("20.00");
        BigDecimal brokerage = rawBrokerage.min(maxBrokerage);
        if (isDelivery && tradeValue.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal minBrokerage = new BigDecimal("5.00");
            brokerage = brokerage.max(minBrokerage);
        }
        brokerage = brokerage.setScale(4, RoundingMode.HALF_UP);

        // 2. STT (Securities Transaction Tax)
        BigDecimal stt = BigDecimal.ZERO;
        if (isDelivery) {
            // Delivery: 0.1% on buy and sell
            stt = tradeValue.multiply(new BigDecimal("0.001")).setScale(4, RoundingMode.HALF_UP);
        } else {
            // Intraday: 0.025% on sell side only
            if (!isBuy) {
                stt = tradeValue.multiply(new BigDecimal("0.00025")).setScale(4, RoundingMode.HALF_UP);
            }
        }

        // 3. Exchange Transaction Charges
        String ex = exchange != null ? exchange.trim().toUpperCase() : "NSE";
        BigDecimal exRate = "BSE".equalsIgnoreCase(ex) ? new BigDecimal("0.0000375") : new BigDecimal("0.0000297");
        BigDecimal exchangeTxnCharges = tradeValue.multiply(exRate).setScale(4, RoundingMode.HALF_UP);

        // 4. SEBI Charges (0.0001% = Rs 10 per crore)
        BigDecimal sebiCharges = tradeValue.multiply(new BigDecimal("0.000001")).setScale(4, RoundingMode.HALF_UP);

        // 5. Stamp Duty (Buy side only)
        BigDecimal stampDuty = BigDecimal.ZERO;
        if (isBuy) {
            BigDecimal stampRate = isDelivery ? new BigDecimal("0.00015") : new BigDecimal("0.00003");
            stampDuty = tradeValue.multiply(stampRate).setScale(4, RoundingMode.HALF_UP);
        }

        // 6. DP Charges (Delivery Sell side only)
        BigDecimal dpCharges = BigDecimal.ZERO;
        if (!isBuy && isDelivery) {
            dpCharges = new BigDecimal("13.50");
        }

        // 7. GST (18% on Brokerage + Exchange Txn + SEBI + DP Charges)
        BigDecimal gstBase = brokerage.add(exchangeTxnCharges).add(sebiCharges).add(dpCharges);
        BigDecimal gst = gstBase.multiply(new BigDecimal("0.18")).setScale(4, RoundingMode.HALF_UP);

        return new ItemizedChargesDto(
                brokerage,
                stt,
                exchangeTxnCharges,
                sebiCharges,
                stampDuty,
                gst,
                dpCharges,
                BigDecimal.ZERO
        );
    }
}
