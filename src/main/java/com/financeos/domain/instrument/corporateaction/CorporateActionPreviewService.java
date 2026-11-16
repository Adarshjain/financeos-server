package com.financeos.domain.instrument.corporateaction;

import com.financeos.api.instrument.dto.CorporateActionPreviewResponse;
import com.financeos.api.instrument.dto.CorporateActionPreviewResponse.HoldingEffect;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows what a corporate action would do to the caller's holdings without keeping it. It runs the real
 * create ({@link CorporateActionService#createCorporateAction}: same validation, same child holdings)
 * and reads the position engine's figures before and after, then marks the transaction rollback-only,
 * so the engine maths is the one that applies actions and nothing is persisted.
 */
@Service
public class CorporateActionPreviewService {

    private final CorporateActionService corporateActionService;
    private final InvestmentService investmentService;
    private final HoldingRepository holdingRepository;
    private final InstrumentRepository instrumentRepository;
    private final CorporateActionRepository corporateActionRepository;

    public CorporateActionPreviewService(CorporateActionService corporateActionService,
                                         InvestmentService investmentService,
                                         HoldingRepository holdingRepository,
                                         InstrumentRepository instrumentRepository,
                                         CorporateActionRepository corporateActionRepository) {
        this.corporateActionService = corporateActionService;
        this.investmentService = investmentService;
        this.holdingRepository = holdingRepository;
        this.instrumentRepository = instrumentRepository;
        this.corporateActionRepository = corporateActionRepository;
    }

    public CorporateActionPreviewResponse preview(UUID instrumentId, CreateCorporateActionRequest request) {
        return preview(instrumentId, request, null);
    }

    /**
     * {@code excludeActionId}: the caller's saved action being edited; it is removed (in the rolled-back
     * transaction) before "before" is read, so the posted action replaces it instead of adding to it.
     */
    @Transactional
    public CorporateActionPreviewResponse preview(UUID instrumentId, CreateCorporateActionRequest request, UUID excludeActionId) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("A corporate action preview needs a signed-in user");
        }
        if (!instrumentRepository.existsById(instrumentId)) {
            throw new ResourceNotFoundException("Instrument", instrumentId);
        }
        UUID childId = request.targetInstrumentId();
        boolean hasChild = (request.type() == CorporateActionType.demerger || request.type() == CorporateActionType.merger)
                && childId != null;

        boolean replacing = excludeActionId != null;
        try {
            if (replacing) {
                corporateActionService.deleteCorporateAction(instrumentId, excludeActionId);
                corporateActionRepository.flush();
            }
            return previewRows(userId, instrumentId, request, childId, hasChild);
        } finally {
            // Always undo: the action and the child holdings it materialised are only for reading the figures.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    private CorporateActionPreviewResponse previewRows(UUID userId, UUID instrumentId, CreateCorporateActionRequest request,
                                                       UUID childId, boolean hasChild) {
        List<Holding> holdings = holdingRepository.findByUser_IdAndInstrument_Id(userId, instrumentId);
        Snapshot beforeSnap = snapshot(userId, holdings, childId, hasChild);

        {
            corporateActionService.createForPreview(instrumentId, request);

            Snapshot afterSnap = snapshot(userId, holdings, childId, hasChild);
            List<HoldingEffect> rows = new ArrayList<>();
            for (int i = 0; i < holdings.size(); i++) {
                Holding h = holdings.get(i);
                HoldingPosition b = beforeSnap.parents().get(i).position();
                HoldingPosition a = afterSnap.parents().get(i).position();
                BigDecimal childQty = null;
                BigDecimal childCost = null;
                BigDecimal fractional = null;
                if (hasChild) {
                    InvestmentService.PositionWithSeedFractional cbRow = beforeSnap.children().get(i);
                    InvestmentService.PositionWithSeedFractional caRow = afterSnap.children().get(i);
                    HoldingPosition cb = cbRow == null ? null : cbRow.position();
                    HoldingPosition ca = caRow == null ? null : caRow.position();
                    childQty = diff(ca == null ? null : ca.openQty(), cb == null ? null : cb.openQty());
                    childCost = diff(ca == null ? null : ca.openCost(), cb == null ? null : cb.openCost());
                    fractional = diff(caRow == null ? null : caRow.seedFractionalRealized(),
                            cbRow == null ? null : cbRow.seedFractionalRealized());
                }
                rows.add(new HoldingEffect(h.getBrokerAccount().getId(), h.getBrokerAccount().getName(),
                        b.openQty(), a.openQty(), b.avgCost(), a.avgCost(), b.openCost(), a.openCost(),
                        hasChild ? childId : null, childQty, childCost, fractional));
            }
            return new CorporateActionPreviewResponse(rows);
        }
    }

    private static UUID instrumentIdOf(List<Holding> parents) {
        return parents.isEmpty() ? null : parents.get(0).getInstrument().getId();
    }

    /**
     * The caller's holdings of every other instrument whose demerger/merger seeds the parents or the target
     * (a child can be the target of several instruments' actions), in two queries however many there are.
     */
    private List<Holding> otherParents(UUID userId, List<Holding> parents, List<Holding> childHoldings, UUID childId,
                                       boolean hasChild, UUID parentInstrumentId) {
        if (parentInstrumentId == null) {
            return List.of();
        }
        List<UUID> targets = new ArrayList<>();
        targets.add(parentInstrumentId);
        if (hasChild) {
            targets.add(childId);
        }
        Set<UUID> others = new LinkedHashSet<>();
        for (CorporateAction ca : corporateActionRepository.findOwnedByTargetInstrumentIds(userId, targets)) {
            UUID source = ca.getInstrument().getId();
            if (!source.equals(parentInstrumentId) && !source.equals(childId)) {
                others.add(source);
            }
        }
        return others.isEmpty() ? List.of() : holdingRepository.findByUser_IdAndInstrument_IdIn(userId, others);
    }

    /** Positions of the parents and, per parent (same order), of the caller's holding in the target at its broker (null: none). */
    private record Snapshot(List<InvestmentService.PositionWithSeedFractional> parents,
                            List<InvestmentService.PositionWithSeedFractional> children) {}

    /**
     * One batch load of the engine inputs for the parents and the target's holdings together (so a
     * child's seeding parent is visible to the engine), instead of per-holding reads.
     */
    private Snapshot snapshot(UUID userId, List<Holding> parents, UUID childId, boolean hasChild) {
        List<Holding> childHoldings = hasChild ? holdingRepository.findByUser_IdAndInstrument_Id(userId, childId) : List.of();
        List<Holding> all = new ArrayList<>(parents);
        all.addAll(childHoldings);
        List<InvestmentService.PositionWithSeedFractional> rows = investmentService.calculateHoldingPositionsWithSeedFractional(
                all, otherParents(userId, parents, childHoldings, childId, hasChild, instrumentIdOf(parents)));
        Map<UUID, InvestmentService.PositionWithSeedFractional> childByBroker = new HashMap<>();
        for (int i = 0; i < childHoldings.size(); i++) {
            childByBroker.put(childHoldings.get(i).getBrokerAccount().getId(), rows.get(parents.size() + i));
        }
        List<InvestmentService.PositionWithSeedFractional> children = new ArrayList<>(parents.size());
        for (Holding p : parents) {
            children.add(childByBroker.get(p.getBrokerAccount().getId()));
        }
        return new Snapshot(rows.subList(0, parents.size()), children);
    }

    private static BigDecimal diff(BigDecimal after, BigDecimal before) {
        return (after == null ? BigDecimal.ZERO : after).subtract(before == null ? BigDecimal.ZERO : before);
    }
}
