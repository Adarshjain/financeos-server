package com.financeos.domain.instrument.price;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.PriceSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface PriceProvider {

    PriceSource source();

    boolean supports(Instrument instrument);

    Map<UUID, PriceQuote> fetch(List<Instrument> instruments);
}
