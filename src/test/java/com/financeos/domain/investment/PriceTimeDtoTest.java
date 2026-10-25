package com.financeos.domain.investment;

import com.financeos.api.instrument.dto.InstrumentPriceResponse;
import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The quote instant reaches the API DTOs as an IST-offset time next to each as-of date. */
class PriceTimeDtoTest {

    private static final Instant AT = Instant.parse("2026-10-13T09:59:58Z");
    private static final Instant PREV_AT = Instant.parse("2026-10-12T09:59:58Z");
    private static final LocalDate D = LocalDate.of(2026, 10, 13);

    private static HoldingPosition position(Instant priceAt) {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Infosys");
        instrument.setAssetClass(AssetClass.EQUITY);
        Account broker = new Account();
        broker.setName("Zerodha");
        Holding h = new Holding();
        h.setId(UUID.randomUUID());
        h.setInstrument(instrument);
        h.setBrokerAccount(broker);
        return new HoldingPosition(h, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("12"), D,
                PriceSource.YAHOO, new BigDecimal("120"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null, null,
                null, null, null, null, priceAt);
    }

    @Test
    void positionCarriesTheLastPriceTimeAsIstOffset() {
        PositionDto dto = position(AT).toPositionDto();

        assertEquals(OffsetDateTime.parse("2026-10-13T15:29:58+05:30"), dto.lastPriceAt());
        assertEquals("+05:30", dto.lastPriceAt().getOffset().getId());
        assertEquals(D, dto.lastPriceAsOf());
    }

    @Test
    void positionWithoutAPriceTimeHasNone() {
        PositionDto dto = position(null).toPositionDto();

        assertNull(dto.lastPriceAt());
        assertNull(dto.previousCloseAt());
    }

    @Test
    void dayChangeCarriesThePreviousCloseTimeAndKeepsTheLastPriceTime() {
        PositionDto dto = position(AT).toPositionDto();
        List<DayChange.PricePoint> closes = List.of(new DayChange.PricePoint(D, new BigDecimal("12"), AT),
                new DayChange.PricePoint(D.minusDays(1), new BigDecimal("10"), PREV_AT));
        DayChange change = DayChange.forPosition(dto.quantity(), dto.lastPrice(), dto.lastPriceAsOf(), closes, D, null);

        PositionDto withChange = dto.withDayChange(change);

        assertEquals(OffsetDateTime.parse("2026-10-12T15:29:58+05:30"), withChange.previousCloseAt());
        assertEquals(OffsetDateTime.parse("2026-10-13T15:29:58+05:30"), withChange.lastPriceAt());
        assertEquals(D.minusDays(1), withChange.previousCloseAsOf());
    }

    @Test
    void aPreviousCloseWithoutATimeHasNone() {
        PositionDto dto = position(AT).toPositionDto();
        List<DayChange.PricePoint> closes = List.of(new DayChange.PricePoint(D, new BigDecimal("12")),
                new DayChange.PricePoint(D.minusDays(1), new BigDecimal("10")));

        PositionDto withChange = dto.withDayChange(
                DayChange.forPosition(dto.quantity(), dto.lastPrice(), dto.lastPriceAsOf(), closes, D, null));

        assertNull(withChange.previousCloseAt());
        assertEquals(D.minusDays(1), withChange.previousCloseAsOf());
    }

    @Test
    void priceHistoryRowsCarryTheTime() {
        Instrument instrument = new Instrument();
        InstrumentPrice feed = new InstrumentPrice(instrument, D, BigDecimal.TEN, PriceSource.YAHOO);
        feed.setPriceTime(AT);
        InstrumentPrice manual = InstrumentPrice.manual(instrument, UUID.randomUUID(), D, BigDecimal.TEN);

        assertEquals(OffsetDateTime.parse("2026-10-13T15:29:58+05:30"), InstrumentPriceResponse.from(feed, null).priceAt());
        assertNull(InstrumentPriceResponse.from(manual, null).priceAt());
    }
}
