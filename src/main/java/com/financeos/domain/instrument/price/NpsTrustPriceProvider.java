package com.financeos.domain.instrument.price;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NAVs of {@code nps_scheme} instruments from the NPS Trust daily file; the instrument's symbol is its scheme code. */
@Component
public class NpsTrustPriceProvider implements PriceProvider {

    private final NpsTrustFeedClient feedClient;

    public NpsTrustPriceProvider(NpsTrustFeedClient feedClient) {
        this.feedClient = feedClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.NPS_TRUST;
    }

    @Override
    public boolean supports(Instrument instrument) {
        return instrument != null && instrument.getType() == InstrumentType.nps_scheme;
    }

    @Override
    public Map<UUID, PriceQuote> fetch(List<Instrument> instruments) {
        Map<UUID, PriceQuote> result = new HashMap<>();
        for (Instrument instrument : instruments) {
            if (!supports(instrument)) {
                continue;
            }
            PriceQuote quote = feedClient.getQuote(instrument.getSymbol());
            if (quote != null) {
                result.put(instrument.getId(), quote);
            }
        }
        return result;
    }
}
