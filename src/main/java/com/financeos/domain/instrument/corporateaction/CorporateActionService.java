package com.financeos.domain.instrument.corporateaction;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final CorporateActionRepository corporateActionRepository;
    private final InstrumentRepository instrumentRepository;
    private final HoldingRepository holdingRepository;
    private final UserRepository userRepository;

    public CorporateActionService(CorporateActionRepository corporateActionRepository,
                                  InstrumentRepository instrumentRepository,
                                  HoldingRepository holdingRepository,
                                  UserRepository userRepository) {
        this.corporateActionRepository = corporateActionRepository;
        this.instrumentRepository = instrumentRepository;
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
    }

    public CorporateActionResponse createCorporateAction(UUID instrumentId, CreateCorporateActionRequest request) {
        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", instrumentId));

        validateRequest(instrumentId, request.type(), request.targetInstrumentId(), request.costAllocationPct(), request.fractionalCashInLieu());

        CorporateAction ca = new CorporateAction();
        ca.setInstrument(instrument);
        ca.setType(request.type());
        ca.setRatioFrom(request.ratioFrom());
        ca.setRatioTo(request.ratioTo());
        ca.setExDate(request.exDate());
        ca.setNotes(request.notes());

        if (request.type() == CorporateActionType.demerger || request.type() == CorporateActionType.merger) {
            Instrument targetInst = instrumentRepository.findById(request.targetInstrumentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Instrument", request.targetInstrumentId()));
            ca.setTargetInstrument(targetInst);
            ca.setCostAllocationPct(request.type() == CorporateActionType.demerger ? request.costAllocationPct() : new BigDecimal("100"));
            ca.setFractionalCashInLieu(request.fractionalCashInLieu());
        } else {
            ca.setTargetInstrument(null);
            ca.setCostAllocationPct(null);
            ca.setFractionalCashInLieu(null);
        }

        CorporateAction saved = corporateActionRepository.save(ca);

        if ((saved.getType() == CorporateActionType.demerger || saved.getType() == CorporateActionType.merger) && saved.getTargetInstrument() != null) {
            materializeChildHoldings(saved.getInstrument().getId(), saved.getTargetInstrument());
        }

        String parentIsin = saved.getInstrument() != null ? saved.getInstrument().getIsin() : "";
        String childIsin = saved.getTargetInstrument() != null ? saved.getTargetInstrument().getIsin() : "";
        String ratio = saved.getRatioFrom() + ":" + saved.getRatioTo();

        log.info("Corporate action created: type={}, parentIsin={}, childIsin={}, ratio={}",
                saved.getType(), parentIsin, childIsin, ratio,
                StructuredArguments.keyValue("event", Events.CA_CREATED),
                StructuredArguments.keyValue("type", saved.getType().name()),
                StructuredArguments.keyValue("parentIsin", parentIsin),
                StructuredArguments.keyValue("childIsin", childIsin),
                StructuredArguments.keyValue("ratio", ratio),
                StructuredArguments.keyValue("cashInLieuInr", saved.getFractionalCashInLieu() != null ? saved.getFractionalCashInLieu().toString() : "0"));

        return CorporateActionResponse.from(saved);
    }

    public CorporateActionResponse updateCorporateAction(UUID instrumentId, UUID id, UpdateCorporateActionRequest request) {
        CorporateAction ca = corporateActionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CorporateAction", id));

        if (!ca.getInstrument().getId().equals(instrumentId)) {
            throw new ResourceNotFoundException("CorporateAction", id);
        }

        validateRequest(instrumentId, request.type(), request.targetInstrumentId(), request.costAllocationPct(), request.fractionalCashInLieu());

        ca.setType(request.type());
        ca.setRatioFrom(request.ratioFrom());
        ca.setRatioTo(request.ratioTo());
        ca.setExDate(request.exDate());
        ca.setNotes(request.notes());

        if (request.type() == CorporateActionType.demerger || request.type() == CorporateActionType.merger) {
            Instrument targetInst = instrumentRepository.findById(request.targetInstrumentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Instrument", request.targetInstrumentId()));
            ca.setTargetInstrument(targetInst);
            ca.setCostAllocationPct(request.type() == CorporateActionType.demerger ? request.costAllocationPct() : new BigDecimal("100"));
            ca.setFractionalCashInLieu(request.fractionalCashInLieu());
        } else {
            ca.setTargetInstrument(null);
            ca.setCostAllocationPct(null);
            ca.setFractionalCashInLieu(null);
        }

        CorporateAction saved = corporateActionRepository.save(ca);

        if ((saved.getType() == CorporateActionType.demerger || saved.getType() == CorporateActionType.merger) && saved.getTargetInstrument() != null) {
            materializeChildHoldings(saved.getInstrument().getId(), saved.getTargetInstrument());
        }

        return CorporateActionResponse.from(saved);
    }

    private void validateRequest(UUID parentInstrumentId, CorporateActionType type, UUID targetInstrumentId, BigDecimal costAllocationPct, BigDecimal fractionalCashInLieu) {
        if (fractionalCashInLieu != null && fractionalCashInLieu.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Fractional cash-in-lieu amount must be greater than or equal to 0.");
        }
        if (type == CorporateActionType.demerger || type == CorporateActionType.merger) {
            if (targetInstrumentId == null) {
                throw new ValidationException("Target instrument is required for corporate action.");
            }
            if (targetInstrumentId.equals(parentInstrumentId)) {
                throw new ValidationException("Target instrument must be different from parent instrument.");
            }
            if (!instrumentRepository.existsById(targetInstrumentId)) {
                throw new ResourceNotFoundException("Instrument", targetInstrumentId);
            }
            if (type == CorporateActionType.demerger) {
                if (costAllocationPct == null ||
                        costAllocationPct.compareTo(BigDecimal.ZERO) <= 0 ||
                        costAllocationPct.compareTo(new BigDecimal("100")) > 0) {
                    throw new ValidationException("Cost allocation percentage must be greater than 0 and less than or equal to 100.");
                }
            }
        }
    }

    private void materializeChildHoldings(UUID parentInstrumentId, Instrument targetInstrument) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return;
        }
        User user = userRepository.getReferenceById(userId);
        List<Holding> parentHoldings = holdingRepository.findByInstrumentId(parentInstrumentId);
        for (Holding parentHolding : parentHoldings) {
            Optional<Holding> existingChild = holdingRepository.findByBrokerAccountIdAndInstrumentId(
                    parentHolding.getBrokerAccount().getId(),
                    targetInstrument.getId()
            );
            if (existingChild.isEmpty()) {
                Holding childHolding = new Holding(parentHolding.getBrokerAccount(), targetInstrument, "Created via corporate action");
                childHolding.setUser(user);
                holdingRepository.save(childHolding);
            }
        }
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

    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getAllCorporateActions() {
        List<CorporateAction> actions = corporateActionRepository.findAllWithInstruments();
        return actions.stream().map(CorporateActionResponse::from).toList();
    }
}
