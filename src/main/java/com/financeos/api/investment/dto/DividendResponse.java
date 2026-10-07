package com.financeos.api.investment.dto;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendReceiptStatus;
import com.financeos.domain.investment.dividend.DividendReceiptWindows;
import com.financeos.domain.investment.dividend.DividendType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DividendResponse(
        UUID id,
        UUID holdingId,
        UUID brokerAccountId,
        String brokerName,
        UUID instrumentId,
        String instrumentName,
        String symbol,
        DividendType type,
        BigDecimal amount,
        @Nullable BigDecimal perUnit,
        @Nullable BigDecimal tds,
        @Nullable LocalDate exDate,
        LocalDate payDate,
        String source,
        @Nullable String notes,
        Instant createdAt,
        /** Derived: see {@link DividendReceiptWindows#derive}. */
        DividendReceiptStatus receiptStatus,
        /** The linked bank credit, when {@code receiptStatus == received}. */
        @Nullable DividendTransactionSummary transaction
) {
    public static DividendResponse from(Dividend dividend, LocalDate today, @Nullable LocalDate coverageEnd) {
        Holding h = dividend.getHolding();
        return new DividendResponse(
                dividend.getId(),
                h.getId(),
                h.getBrokerAccount().getId(),
                h.getBrokerAccount().getName(),
                h.getInstrument().getId(),
                h.getInstrument().getName(),
                h.getInstrument().getSymbol(),
                dividend.getType(),
                dividend.getAmount(),
                dividend.getPerUnit(),
                dividend.getTds(),
                dividend.getExDate(),
                dividend.getPayDate(),
                dividend.getSource(),
                dividend.getNotes(),
                dividend.getCreatedAt(),
                DividendReceiptWindows.derive(dividend, today, coverageEnd),
                DividendTransactionSummary.from(dividend.getTransaction())
        );
    }
}
