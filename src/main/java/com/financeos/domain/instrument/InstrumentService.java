package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentPriceResponse;
import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.UpsertPriceRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceRepository priceRepository;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentPriceRepository priceRepository) {
        this.instrumentRepository = instrumentRepository;
        this.priceRepository = priceRepository;
    }

    @Transactional(readOnly = true)
    public List<InstrumentResponse> searchInstruments(String search, InstrumentType type) {
        List<Instrument> instruments = instrumentRepository.searchInstruments(search, type);
        return instruments.stream()
                .map(inst -> {
                    Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(inst.getId());
                    return InstrumentResponse.from(inst, latestPrice);
                })
                .toList();
    }

    public InstrumentResponse createInstrument(InstrumentRequest request) {
        if (request.isin() != null && !request.isin().isBlank()) {
            Optional<Instrument> existing = instrumentRepository.findByIsin(request.isin().trim());
            if (existing.isPresent()) {
                Instrument inst = existing.get();
                Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(inst.getId());
                return InstrumentResponse.from(inst, latestPrice);
            }
        }

        Instrument instrument = new Instrument();
        instrument.setType(request.type());
        instrument.setName(request.name());
        instrument.setSymbol(request.symbol());
        instrument.setExchange(request.exchange());
        instrument.setIsin(request.isin() != null ? request.isin().trim() : null);
        instrument.setAmfiCode(request.amfiCode());
        instrument.setYahooSymbol(request.yahooSymbol());
        if (request.currency() != null && !request.currency().isBlank()) {
            instrument.setCurrency(request.currency());
        }

        Instrument saved = instrumentRepository.save(instrument);
        return InstrumentResponse.from(saved, Optional.empty());
    }

    @Transactional(readOnly = true)
    public InstrumentResponse getInstrumentById(UUID id) {
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", id));
        Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(id);
        return InstrumentResponse.from(instrument, latestPrice);
    }

    public InstrumentResponse updateInstrument(UUID id, InstrumentRequest request) {
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", id));

        instrument.setType(request.type());
        instrument.setName(request.name());
        instrument.setSymbol(request.symbol());
        instrument.setExchange(request.exchange());
        instrument.setIsin(request.isin() != null ? request.isin().trim() : null);
        instrument.setAmfiCode(request.amfiCode());
        instrument.setYahooSymbol(request.yahooSymbol());
        if (request.currency() != null && !request.currency().isBlank()) {
            instrument.setCurrency(request.currency());
        }

        Instrument saved = instrumentRepository.save(instrument);
        Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(id);
        return InstrumentResponse.from(saved, latestPrice);
    }

    public InstrumentResponse upsertPrice(UUID id, UpsertPriceRequest request) {
        Instrument instrument = instrumentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", id));

        LocalDate asOf = request.asOf() != null ? request.asOf() : LocalDate.now();
        Optional<InstrumentPrice> existingPrice = priceRepository.findByInstrumentIdAndAsOf(id, asOf);

        InstrumentPrice price;
        if (existingPrice.isPresent()) {
            price = existingPrice.get();
            price.setClose(request.price());
            price.setSource(PriceSource.MANUAL);
        } else {
            price = new InstrumentPrice(instrument, asOf, request.price(), PriceSource.MANUAL);
        }
        priceRepository.save(price);

        Optional<InstrumentPrice> latestPrice = priceRepository.findTopByInstrumentIdOrderByAsOfDesc(id);
        return InstrumentResponse.from(instrument, latestPrice);
    }

    @Transactional(readOnly = true)
    public List<InstrumentPriceResponse> getPriceHistory(UUID instrumentId, LocalDate from, LocalDate to) {
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new ResourceNotFoundException("Instrument", instrumentId);
        }
        List<InstrumentPrice> prices = priceRepository.findPriceHistory(instrumentId, from, to);
        return prices.stream().map(InstrumentPriceResponse::from).toList();
    }
}
