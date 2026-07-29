package com.financeos.domain.instrument.price;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AmfiPriceProvider implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(AmfiPriceProvider.class);

    private final PriceProperties priceProperties;
    private final AmfiFeedClient amfiFeedClient;

    public AmfiPriceProvider(PriceProperties priceProperties, AmfiFeedClient amfiFeedClient) {
        this.priceProperties = priceProperties;
        this.amfiFeedClient = amfiFeedClient;
    }

    @Override
    public PriceSource source() {
        return PriceSource.AMFI;
    }

    @Override
    public boolean supports(Instrument instrument) {
        return instrument != null && instrument.getType() == InstrumentType.mutual_fund;
    }

    @Override
    public Map<UUID, PriceQuote> fetch(List<Instrument> instruments) {
        Map<UUID, PriceQuote> result = new HashMap<>();
        List<Instrument> targetInstruments = instruments.stream()
                .filter(this::supports)
                .toList();

        if (targetInstruments.isEmpty()) {
            return result;
        }

        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("amfi");
        if (props != null && !props.isEnabled()) {
            log.info("AMFI price provider is disabled in configuration.");
            return result;
        }

        for (Instrument inst : targetInstruments) {
            PriceQuote quote = null;
            if (inst.getAmfiCode() != null && !inst.getAmfiCode().isBlank()) {
                quote = amfiFeedClient.getQuoteBySchemeCode(inst.getAmfiCode());
            }
            if (quote == null && inst.getIsin() != null && !inst.getIsin().isBlank()) {
                quote = amfiFeedClient.getQuoteByIsin(inst.getIsin());
            }

            if (quote != null) {
                result.put(inst.getId(), quote);
            } else {
                log.debug("No AMFI NAV quote matched for instrument: {} (amfiCode={}, isin={})",
                        inst.getName(), inst.getAmfiCode(), inst.getIsin());
            }
        }

        return result;
    }
}
