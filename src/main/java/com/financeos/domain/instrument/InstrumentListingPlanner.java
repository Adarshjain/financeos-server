package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.price.PriceProvider;
import com.financeos.domain.instrument.price.PriceQuote;
import com.financeos.domain.instrument.search.InstrumentSearchProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The network half of an NSE <-> BSE switch, run BEFORE the transactional move: it reads what it needs
 * through short repository calls, then asks the search and price feeds with no transaction (and so no
 * database connection) open. Deliberately not {@code @Transactional}, and it must not be called from a
 * transaction. Nothing is written here, so a refusal changes nothing.
 */
@Service
public class InstrumentListingPlanner {

    private final InstrumentRepository instrumentRepository;
    private final AssetClassOverrideService overrideService;
    private final List<InstrumentSearchProvider> searchProviders;
    private final List<PriceProvider> priceProviders;

    public InstrumentListingPlanner(InstrumentRepository instrumentRepository, AssetClassOverrideService overrideService,
                                    List<InstrumentSearchProvider> searchProviders, List<PriceProvider> priceProviders) {
        this.instrumentRepository = instrumentRepository;
        this.overrideService = overrideService;
        this.searchProviders = searchProviders;
        this.priceProviders = priceProviders;
    }

    /**
     * The Yahoo symbol, confirmed by a price, of the listing the edit switches the signed-in user to; null
     * when the edit is no switch or the listing already has its own row (nothing to verify). 400 when the
     * feed has no price for the listing, or cannot be asked.
     */
    @Nullable
    public String verifiedListingSymbol(UUID id, InstrumentRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        Instrument source = userId == null ? null : instrumentRepository.findById(id).orElse(null);
        if (source == null || InstrumentService.identifiersChanged(source, request)) {
            return null;
        }
        DisplayChanges changes = DisplayChanges.between(
                InstrumentOverrides.orNone(overrideService.overridesFor(userId)), source, request);
        if (!InstrumentRepointService.isListingSwitch(source, request, changes)) {
            return null;
        }
        String exchange = request.exchange().trim().toUpperCase(Locale.ROOT);
        if (instrumentRepository.findByIsinAndExchange(source.getIsin().trim(), exchange).isPresent()) {
            return null;
        }
        String yahoo = null;
        for (InstrumentSearchProvider provider : searchProviders) {
            if (yahoo != null || !provider.supports(source.getType())) {
                continue;
            }
            try {
                for (InstrumentCandidate c : provider.search(source.getIsin().trim(), source.getType())) {
                    if (exchange.equalsIgnoreCase(c.exchange()) && c.yahooSymbol() != null && !c.yahooSymbol().isBlank()
                            && (c.isin() == null || c.isin().isBlank()
                                    || c.isin().trim().equalsIgnoreCase(source.getIsin().trim()))) {
                        yahoo = c.yahooSymbol().trim();
                        break;
                    }
                }
            } catch (Exception e) {
                // Fail soft, as the catalog search does: the derived symbol is tried instead.
            }
        }
        if (yahoo == null) {
            yahoo = InstrumentRepointService.listingYahooSymbol(source, exchange);
        }
        String current = source.getExchange() != null && !source.getExchange().isBlank()
                ? source.getExchange().trim() : "its current exchange";
        String none = String.format("No %s listing with a price was found for %s. It stays on %s.", exchange,
                source.getName(), current);
        if (yahoo == null) {
            throw new ValidationException(none);
        }
        Instrument probe = new Instrument();
        probe.setId(UUID.randomUUID());
        probe.setType(source.getType());
        probe.setYahooSymbol(yahoo);
        PriceProvider provider = priceProviders.stream()
                .filter(p -> p.source() == PriceSource.YAHOO && p.supports(probe)).findFirst().orElse(null);
        Map<UUID, PriceQuote> quotes = provider == null ? Map.of() : provider.fetch(List.of(probe));
        if (quotes.containsKey(probe.getId())) {
            return yahoo;
        }
        if (provider != null && provider.takeNotFound().contains(probe.getId())) {
            throw new ValidationException(none);
        }
        throw new ValidationException(String.format("Couldn't check the %s price right now. Try again in a few minutes.",
                exchange));
    }
}
