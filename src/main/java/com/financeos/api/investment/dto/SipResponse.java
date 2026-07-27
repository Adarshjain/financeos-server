package com.financeos.api.investment.dto;

import com.financeos.domain.investment.sip.Sip;
import com.financeos.domain.investment.sip.SipFrequency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SipResponse(
        UUID id,
        UUID brokerAccountId,
        String brokerName,
        UUID instrumentId,
        String instrumentName,
        String symbol,
        BigDecimal amount,
        SipFrequency frequency,
        Integer dayOfMonth,
        LocalDate startDate,
        LocalDate endDate,
        boolean active,
        String notes,
        SipProgressDto progress,
        Instant createdAt
) {
    public static SipResponse from(Sip sip, SipProgressDto progress) {
        return new SipResponse(
                sip.getId(),
                sip.getBrokerAccount().getId(),
                sip.getBrokerAccount().getName(),
                sip.getInstrument().getId(),
                sip.getInstrument().getName(),
                sip.getInstrument().getSymbol(),
                sip.getAmount(),
                sip.getFrequency(),
                sip.getDayOfMonth(),
                sip.getStartDate(),
                sip.getEndDate(),
                sip.isActive(),
                sip.getNotes(),
                progress,
                sip.getCreatedAt()
        );
    }
}
