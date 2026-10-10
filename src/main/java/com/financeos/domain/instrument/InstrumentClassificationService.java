package com.financeos.domain.instrument;

import com.financeos.domain.instrument.price.AmfiFeedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Fills an instrument's stored asset class: mutual funds from their AMFI scheme-category header
 * (source AMFI), everything else — and funds the feed does not list — by {@link AssetClassifier}'s
 * rules (source RULE). Runs on price refresh and when an instrument is created, edited or picked from
 * the catalog. A user's own choice of class is never stored here (the instrument is shared by every
 * user): it lives in {@link UserInstrumentOverride} and readers apply it over this classification.
 */
@Component
public class InstrumentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentClassificationService.class);

    private final AmfiFeedClient amfiFeedClient;

    public InstrumentClassificationService(AmfiFeedClient amfiFeedClient) {
        this.amfiFeedClient = amfiFeedClient;
    }

    /**
     * Classifies {@code instrument} in place. Returns true when a stored field changed (the caller
     * saves); false when nothing changed.
     */
    public boolean classify(Instrument instrument) {
        if (instrument == null) {
            return false;
        }
        String category = instrument.getSchemeCategory();
        if (instrument.getType() == InstrumentType.mutual_fund) {
            String fromFeed = schemeCategory(instrument);
            if (fromFeed != null) {
                category = fromFeed;
            }
        }
        AssetClassSource source = instrument.getType() == InstrumentType.mutual_fund
                && category != null && !category.isBlank()
                ? AssetClassSource.AMFI
                : AssetClassSource.RULE;
        AssetClass assetClass = AssetClassifier.classify(instrument.getType(), category, instrument.getName());
        boolean changed = !Objects.equals(category, instrument.getSchemeCategory())
                || assetClass != instrument.getAssetClass()
                || source != instrument.getAssetClassSource();
        if (changed) {
            instrument.setSchemeCategory(category);
            instrument.setAssetClass(assetClass);
            instrument.setAssetClassSource(source);
        }
        return changed;
    }

    private String schemeCategory(Instrument instrument) {
        try {
            return amfiFeedClient.getSchemeCategory(instrument.getAmfiCode(), instrument.getIsin());
        } catch (RuntimeException e) {
            // Classification is best effort: a feed problem must never fail a save or a refresh.
            log.warn("AMFI category lookup failed for instrument {}: {}", instrument.getId(), e.getMessage());
            return null;
        }
    }
}
