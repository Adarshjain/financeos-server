package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.investment.sip.Sip;
import com.financeos.domain.investment.sip.SipFrequency;

import org.springframework.lang.Nullable;

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
        @Nullable String symbol,
        BigDecimal amount,
        SipFrequency frequency,
        @Nullable Integer dayOfMonth,
        LocalDate startDate,
        @Nullable LocalDate endDate,
        boolean active,
        @Nullable String notes,
        SipProgressDto progress,
        Instant createdAt
) {
    public static SipResponse from(Sip sip, SipProgressDto progress) {
        return from(sip, progress, InstrumentOverrides.NONE);
    }

    /** The SIP with its instrument's name and symbol as {@code overrides}' user sees them. */
    public static SipResponse from(Sip sip, SipProgressDto progress, InstrumentOverrides overrides) {
        return new SipResponse(
                sip.getId(),
                sip.getBrokerAccount().getId(),
                sip.getBrokerAccount().getName(),
                sip.getInstrument().getId(),
                overrides.name(sip.getInstrument()),
                overrides.symbol(sip.getInstrument()),
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
