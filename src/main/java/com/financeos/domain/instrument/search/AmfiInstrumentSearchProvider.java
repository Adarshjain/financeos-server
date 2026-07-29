package com.financeos.domain.instrument.search;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.price.AmfiFeedClient;
import com.financeos.domain.instrument.price.AmfiScheme;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AmfiInstrumentSearchProvider implements InstrumentSearchProvider {

    private final AmfiFeedClient amfiFeedClient;

    public AmfiInstrumentSearchProvider(AmfiFeedClient amfiFeedClient) {
        this.amfiFeedClient = amfiFeedClient;
    }

    @Override
    public boolean supports(InstrumentType type) {
        return type == null || type == InstrumentType.mutual_fund;
    }

    @Override
    public List<InstrumentCandidate> search(String query, InstrumentType type) {
        if (type != null && type != InstrumentType.mutual_fund) {
            return List.of();
        }

        if (query == null || query.isBlank()) {
            return List.of();
        }

        String lowerQuery = query.trim().toLowerCase();

        return amfiFeedClient.all().stream()
                .filter(scheme -> scheme.name() != null && scheme.name().toLowerCase().contains(lowerQuery))
                .limit(15)
                .map(this::toCandidate)
                .toList();
    }

    private InstrumentCandidate toCandidate(AmfiScheme scheme) {
        InstrumentCandidate.PricePreview preview = scheme.nav() != null
                ? new InstrumentCandidate.PricePreview(scheme.nav(), scheme.navDate())
                : null;

        return new InstrumentCandidate(
                "AMFI",
                InstrumentType.mutual_fund,
                scheme.name(),
                null,
                null,
                scheme.isin(),
                scheme.schemeCode(),
                null,
                "INR",
                preview,
                null
        );
    }
}
