package com.financeos.domain.instrument.corporateaction;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CorporateActionService {

    private final CorporateActionRepository corporateActionRepository;
    private final InstrumentRepository instrumentRepository;

    public CorporateActionService(CorporateActionRepository corporateActionRepository,
                                  InstrumentRepository instrumentRepository) {
        this.corporateActionRepository = corporateActionRepository;
        this.instrumentRepository = instrumentRepository;
    }

    public CorporateActionResponse createCorporateAction(UUID instrumentId, CreateCorporateActionRequest request) {
        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", instrumentId));

        CorporateAction ca = new CorporateAction();
        ca.setInstrument(instrument);
        ca.setType(request.type());
        ca.setRatioFrom(request.ratioFrom());
        ca.setRatioTo(request.ratioTo());
        ca.setExDate(request.exDate());
        ca.setNotes(request.notes());

        CorporateAction saved = corporateActionRepository.save(ca);
        return CorporateActionResponse.from(saved);
    }

    public CorporateActionResponse updateCorporateAction(UUID instrumentId, UUID id, UpdateCorporateActionRequest request) {
        CorporateAction ca = corporateActionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CorporateAction", id));

        if (!ca.getInstrument().getId().equals(instrumentId)) {
            throw new ResourceNotFoundException("CorporateAction", id);
        }

        ca.setType(request.type());
        ca.setRatioFrom(request.ratioFrom());
        ca.setRatioTo(request.ratioTo());
        ca.setExDate(request.exDate());
        ca.setNotes(request.notes());

        CorporateAction saved = corporateActionRepository.save(ca);
        return CorporateActionResponse.from(saved);
    }

    public void deleteCorporateAction(UUID instrumentId, UUID id) {
        CorporateAction ca = corporateActionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CorporateAction", id));

        if (!ca.getInstrument().getId().equals(instrumentId)) {
            throw new ResourceNotFoundException("CorporateAction", id);
        }

        corporateActionRepository.delete(ca);
    }

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(UUID instrumentId) {
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new ResourceNotFoundException("Instrument", instrumentId);
        }
        List<CorporateAction> actions = corporateActionRepository.findByInstrumentIdOrderByExDateAsc(instrumentId);
        return actions.stream().map(CorporateActionResponse::from).toList();
    }
}
