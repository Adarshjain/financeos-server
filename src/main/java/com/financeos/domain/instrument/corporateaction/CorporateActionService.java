package com.financeos.domain.instrument.corporateaction;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A user's own corporate actions. Every read and write is the caller's: another user's action is a
 * 404, the list holds only the caller's, and the child holdings a demerger/merger creates are the
 * caller's own (one per broker where they hold the parent). Instruments stay the shared catalog.
 */
@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final CorporateActionRepository corporateActionRepository;
    private final InstrumentRepository instrumentRepository;
    private final HoldingRepository holdingRepository;
    private final UserRepository userRepository;
    private final AssetClassOverrideService overrideService;

    public CorporateActionService(CorporateActionRepository corporateActionRepository,
                                  InstrumentRepository instrumentRepository,
                                  HoldingRepository holdingRepository,
                                  UserRepository userRepository,
                                  AssetClassOverrideService overrideService) {
        this.overrideService = overrideService;
        this.corporateActionRepository = corporateActionRepository;
        this.instrumentRepository = instrumentRepository;
        this.holdingRepository = holdingRepository;
        this.userRepository = userRepository;
    }

    /** To tell an emptied-out child holding from one with trades; null in unit tests (then none is removed). */
    private com.financeos.domain.investment.InvestmentTransactionRepository transactionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactionRepository(com.financeos.domain.investment.InvestmentTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** To tell a child holding with dividends from an empty one; null in unit tests (then none is removed). */
    private com.financeos.domain.investment.dividend.DividendRepository dividendRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDividendRepository(com.financeos.domain.investment.dividend.DividendRepository dividendRepository) {
        this.dividendRepository = dividendRepository;
    }

    private static final String NOTE_CHILD_HOLDING = "Created via corporate action";

    private static final String DUPLICATE_MESSAGE = "This action is already recorded for that date";
    private static final String DELISTED_ONCE_MESSAGE = "A delisting is already recorded for this instrument";

    /** A delisting has no ratio: it is stored as 1/1 whatever the request sends. */
    private static Integer ratioOf(CorporateActionType type, Integer requested) {
        return type == CorporateActionType.delisted ? Integer.valueOf(1) : requested;
    }

    private static UUID requireUser() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("A corporate action needs a signed-in user");
        }
        return userId;
    }

    /** The caller's action {@code id} on {@code instrumentId}; another user's, or one on another instrument, is a 404. */
    private CorporateAction ownAction(UUID userId, UUID instrumentId, UUID id) {
        CorporateAction ca = corporateActionRepository.findByIdAndUser_Id(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("CorporateAction", id));
        if (!ca.getInstrument().getId().equals(instrumentId)) {
            throw new ResourceNotFoundException("CorporateAction", id);
        }
        return ca;
    }

    public CorporateActionResponse createCorporateAction(UUID instrumentId, CreateCorporateActionRequest request) {
        return create(instrumentId, request, true);
    }

    /**
     * As {@link #createCorporateAction} without the same-day duplicate check, for the rolled-back preview (which shows
     * what a same-day action would do to the figures).
     */
    public CorporateActionResponse createForPreview(UUID instrumentId, CreateCorporateActionRequest request) {
        return create(instrumentId, request, false);
    }

    private CorporateActionResponse create(UUID instrumentId, CreateCorporateActionRequest request, boolean checkDuplicate) {
        UUID userId = requireUser();
        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", instrumentId));

        validateRequest(instrumentId, request.type(), request.ratioFrom(), request.ratioTo(), request.targetInstrumentId(), request.costAllocationPct(), request.fractionalCashInLieu());
        if (checkDuplicate && sameDay(userId, instrumentId, request.type(), request.exDate(), request.targetInstrumentId(), null)) {
            throw new ValidationException(DUPLICATE_MESSAGE);
        }
        if (request.type() == CorporateActionType.delisted && corporateActionRepository.existsOfType(userId, instrumentId, request.type())) {
            throw new ValidationException(DELISTED_ONCE_MESSAGE);
        }

        CorporateAction ca = new CorporateAction();
        ca.setUser(userRepository.getReferenceById(userId));
        ca.setInstrument(instrument);
        ca.setType(request.type());
        ca.setRatioFrom(ratioOf(request.type(), request.ratioFrom()));
        ca.setRatioTo(ratioOf(request.type(), request.ratioTo()));
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
            materializeChildHoldings(userId, saved.getInstrument().getId(), saved.getTargetInstrument());
        }

        registerCreatedLog(saved);

        return CorporateActionResponse.from(saved, overrideService.overridesForCurrentUser());
    }

    /**
     * Records a split or bonus the detector found for {@code userId} (source {@code auto}), outside a request (null when the user already has the same action that day): the
     * owner is passed in, not read from the signed-in user. The caller has checked the ratio; the post-commit log
     * is the same as a manual create.
     */
    public CorporateAction createDetected(UUID userId, UUID instrumentId, CorporateActionType type,
                                          int ratioFrom, int ratioTo, java.time.LocalDate exDate, String notes) {
        if (corporateActionRepository.existsSameDay(userId, instrumentId, type, exDate)) {
            log.info("Detected corporate action skipped: already recorded for that date");
            return null;
        }
        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", instrumentId));
        CorporateAction ca = new CorporateAction();
        ca.setUser(userRepository.getReferenceById(userId));
        ca.setInstrument(instrument);
        ca.setType(type);
        ca.setRatioFrom(ratioFrom);
        ca.setRatioTo(ratioTo);
        ca.setExDate(exDate);
        ca.setNotes(notes);
        ca.setSource(CorporateAction.SOURCE_AUTO);
        CorporateAction saved = corporateActionRepository.save(ca);
        registerCreatedLog(saved);
        return saved;
    }

    /**
     * Records a demerger or merger the detector verified for {@code userId} (source {@code auto}), outside a request:
     * {@code targetId} is the child or acquirer, {@code costPct} the child's share of the cost (a merger is 100). Null when the
     * user already has this action that day for that target. The child holdings are created as for a manual one.
     */
    public CorporateAction createDetectedWithTarget(UUID userId, UUID instrumentId, CorporateActionType type, int ratioFrom, int ratioTo,
                                                    java.time.LocalDate exDate, String notes, UUID targetId, BigDecimal costPct) {
        if (type != CorporateActionType.demerger && type != CorporateActionType.merger) {
            throw new ValidationException("Only a demerger or merger has a target");
        }
        if (sameDay(userId, instrumentId, type, exDate, targetId, null)) {
            log.info("Detected corporate action skipped: already recorded for that date");
            return null;
        }
        validateRequest(instrumentId, type, ratioFrom, ratioTo, targetId, type == CorporateActionType.demerger ? costPct : null, null);
        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", instrumentId));
        Instrument target = instrumentRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Instrument", targetId));
        CorporateAction ca = new CorporateAction();
        ca.setUser(userRepository.getReferenceById(userId));
        ca.setInstrument(instrument);
        ca.setType(type);
        ca.setRatioFrom(ratioFrom);
        ca.setRatioTo(ratioTo);
        ca.setExDate(exDate);
        ca.setNotes(notes);
        ca.setTargetInstrument(target);
        ca.setCostAllocationPct(type == CorporateActionType.demerger ? costPct : new BigDecimal("100"));
        ca.setSource(CorporateAction.SOURCE_AUTO);
        CorporateAction saved = corporateActionRepository.save(ca);
        materializeChildHoldings(userId, instrumentId, target);
        registerCreatedLog(saved);
        return saved;
    }

    /**
     * Whether the user already has this action that day: same instrument, type and date, and for a demerger or merger
     * the same target too (two demergers of one stock on one date into different companies are legitimate).
     * {@code excludeId} is the action being edited, or null.
     */
    private boolean sameDay(UUID userId, UUID instrumentId, CorporateActionType type, java.time.LocalDate exDate,
                            UUID targetId, UUID excludeId) {
        boolean withTarget = (type == CorporateActionType.demerger || type == CorporateActionType.merger) && targetId != null;
        if (withTarget) {
            return excludeId == null
                    ? corporateActionRepository.existsSameDayWithTarget(userId, instrumentId, type, exDate, targetId)
                    : corporateActionRepository.existsOtherSameDayWithTarget(userId, instrumentId, type, exDate, targetId, excludeId);
        }
        return excludeId == null
                ? corporateActionRepository.existsSameDay(userId, instrumentId, type, exDate)
                : corporateActionRepository.existsOtherSameDay(userId, instrumentId, type, exDate, excludeId);
    }

    /** Logs the creation once the surrounding transaction commits (at once when there is none). */
    private void registerCreatedLog(CorporateAction saved) {
        String parentIsin = saved.getInstrument() != null ? saved.getInstrument().getIsin() : "";
        String childIsin = saved.getTargetInstrument() != null ? saved.getTargetInstrument().getIsin() : "";
        String ratio = saved.getRatioFrom() + ":" + saved.getRatioTo();

        // After commit only: a rolled-back preview (CorporateActionPreviewService) must log nothing.
        Runnable emit = () -> {
            log.info("Corporate action created: type={}, parentIsin={}, childIsin={}, ratio={}",
                    saved.getType(), parentIsin, childIsin, ratio,
                    StructuredArguments.keyValue("event", Events.CA_CREATED),
                    StructuredArguments.keyValue("type", saved.getType().name()),
                    StructuredArguments.keyValue("parentIsin", parentIsin),
                    StructuredArguments.keyValue("childIsin", childIsin),
                    StructuredArguments.keyValue("ratio", ratio),
                    StructuredArguments.keyValue("cashInLieuInr", saved.getFractionalCashInLieu() != null ? saved.getFractionalCashInLieu().toString() : "0"));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit.run();
                }
            });
        } else {
            emit.run();
        }
    }

    public CorporateActionResponse updateCorporateAction(UUID instrumentId, UUID id, UpdateCorporateActionRequest request) {
        UUID userId = requireUser();
        CorporateAction ca = ownAction(userId, instrumentId, id);

        validateRequest(instrumentId, request.type(), request.ratioFrom(), request.ratioTo(), request.targetInstrumentId(), request.costAllocationPct(), request.fractionalCashInLieu());
        if (sameDay(userId, instrumentId, request.type(), request.exDate(), request.targetInstrumentId(), id)) {
            throw new ValidationException(DUPLICATE_MESSAGE);
        }
        if (request.type() == CorporateActionType.delisted && corporateActionRepository.existsOtherOfType(userId, instrumentId, request.type(), id)) {
            throw new ValidationException(DELISTED_ONCE_MESSAGE);
        }

        ca.setType(request.type());
        ca.setRatioFrom(ratioOf(request.type(), request.ratioFrom()));
        ca.setRatioTo(ratioOf(request.type(), request.ratioTo()));
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
            materializeChildHoldings(userId, saved.getInstrument().getId(), saved.getTargetInstrument());
        }

        return CorporateActionResponse.from(saved, overrideService.overridesForCurrentUser());
    }

    private void validateRequest(UUID parentInstrumentId, CorporateActionType type, Integer ratioFrom, Integer ratioTo, UUID targetInstrumentId, BigDecimal costAllocationPct, BigDecimal fractionalCashInLieu) {
        if (fractionalCashInLieu != null && fractionalCashInLieu.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Fractional cash-in-lieu amount must be greater than or equal to 0.");
        }
        if (type == CorporateActionType.delisted && (targetInstrumentId != null || costAllocationPct != null)) {
            throw new ValidationException("A delisting has no target instrument or cost split.");
        }
        if (type == CorporateActionType.bonus && ratioFrom != null && ratioTo != null && ratioTo <= ratioFrom) {
            throw new ValidationException("A bonus needs more units after than before: for a 1:1 bonus enter 1 \u2192 2.");
        }
        if (type == CorporateActionType.split && ratioFrom != null && ratioTo != null && ratioTo.equals(ratioFrom)) {
            throw new ValidationException("A split needs different units before and after: for a 1:2 split enter 1 \u2192 2.");
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

    /**
     * Gives {@code userId} a holding of the demerger/merger target at every broker where they hold the
     * parent (where they have none yet), so the seeded shares have a holding to land in. Only the
     * caller's own parent holdings: other users' actions are their own.
     */
    private void materializeChildHoldings(UUID userId, UUID parentInstrumentId, Instrument targetInstrument) {
        User user = userRepository.getReferenceById(userId);
        List<Holding> parentHoldings = holdingRepository.findByUser_IdAndInstrument_Id(userId, parentInstrumentId);
        for (Holding parentHolding : parentHoldings) {
            Optional<Holding> existingChild = holdingRepository.findByBrokerAccountIdAndInstrumentId(
                    parentHolding.getBrokerAccount().getId(),
                    targetInstrument.getId()
            );
            if (existingChild.isEmpty()) {
                Holding childHolding = new Holding(parentHolding.getBrokerAccount(), targetInstrument, NOTE_CHILD_HOLDING);
                childHolding.setUser(user);
                holdingRepository.save(childHolding);
            }
        }
    }

    public void deleteCorporateAction(UUID instrumentId, UUID id) {
        UUID userId = requireUser();
        CorporateAction ca = ownAction(userId, instrumentId, id);
        Instrument target = (ca.getType() == CorporateActionType.demerger || ca.getType() == CorporateActionType.merger)
                ? ca.getTargetInstrument() : null;
        corporateActionRepository.delete(ca);
        if (target != null) {
            removeEmptyChildHoldings(userId, target);
        }
    }

    /**
     * After a demerger or merger is deleted: removes the user's holding of its target only when the action created it and it has
     * no trades, no dividends and no other action of the user still targets the instrument. Anything else is kept.
     */
    private void removeEmptyChildHoldings(UUID userId, Instrument target) {
        if (transactionRepository == null || dividendRepository == null) {
            return;
        }
        corporateActionRepository.flush();
        if (!corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(userId, target.getId()).isEmpty()) {
            return;
        }
        for (Holding h : holdingRepository.findByUser_IdAndInstrument_Id(userId, target.getId())) {
            if (NOTE_CHILD_HOLDING.equals(h.getNotes()) && transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId()).isEmpty()
                    && dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(h.getId()).isEmpty()) {
                holdingRepository.delete(h);
            }
        }
    }

    /** The caller's own corporate actions on the instrument, oldest first; 404 for an unknown instrument. */
    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getCorporateActions(UUID instrumentId) {
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new ResourceNotFoundException("Instrument", instrumentId);
        }
        UUID userId = UserContext.getCurrentUserId();
        List<CorporateAction> actions = userId == null ? List.of()
                : corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(userId, instrumentId);
        InstrumentOverrides overrides = actions.isEmpty() ? InstrumentOverrides.NONE : overrideService.overridesForCurrentUser();
        return actions.stream().map(ca -> CorporateActionResponse.from(ca, overrides)).toList();
    }

    /** The caller's own corporate actions, newest first (none without a signed-in user). */
    @Transactional(readOnly = true)
    public List<CorporateActionResponse> getAllCorporateActions() {
        UUID userId = UserContext.getCurrentUserId();
        List<CorporateAction> actions = userId == null ? List.of()
                : corporateActionRepository.findAllOwnedWithInstruments(userId);
        InstrumentOverrides overrides = actions.isEmpty() ? InstrumentOverrides.NONE : overrideService.overridesForCurrentUser();
        return actions.stream().map(ca -> CorporateActionResponse.from(ca, overrides)).toList();
    }
}
