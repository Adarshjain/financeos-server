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

    /** Quotes for the intraday live job: cheap and best-effort; providers may skip slow fallbacks. */
    default Map<UUID, PriceQuote> fetchLive(List<Instrument> instruments) {
        return fetch(instruments);
    }
}
