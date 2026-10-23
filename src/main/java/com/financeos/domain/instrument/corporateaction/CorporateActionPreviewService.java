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
import java.util.List;
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
        List<HoldingPosition> before = new ArrayList<>();
        List<HoldingPosition> childBefore = new ArrayList<>();
        List<BigDecimal> fractionalBefore = new ArrayList<>();
        for (Holding h : holdings) {
            before.add(investmentService.calculateHoldingPosition(h));
            HoldingPosition cp = hasChild ? childPosition(h, childId) : null;
            childBefore.add(cp);
            fractionalBefore.add(cp == null ? null : investmentService.seedFractionalRealized(cp.holding()));
        }

        {
            corporateActionService.createCorporateAction(instrumentId, request);

            List<HoldingEffect> rows = new ArrayList<>();
            for (int i = 0; i < holdings.size(); i++) {
                Holding h = holdings.get(i);
                HoldingPosition b = before.get(i);
                HoldingPosition a = investmentService.calculateHoldingPosition(h);
                BigDecimal childQty = null;
                BigDecimal childCost = null;
                BigDecimal fractional = null;
                if (hasChild) {
                    HoldingPosition cb = childBefore.get(i);
                    HoldingPosition ca = childPosition(h, childId);
                    childQty = diff(ca == null ? null : ca.openQty(), cb == null ? null : cb.openQty());
                    childCost = diff(ca == null ? null : ca.openCost(), cb == null ? null : cb.openCost());
                    fractional = diff(ca == null ? null : investmentService.seedFractionalRealized(ca.holding()),
                            fractionalBefore.get(i));
                }
                rows.add(new HoldingEffect(h.getBrokerAccount().getId(), h.getBrokerAccount().getName(),
                        b.openQty(), a.openQty(), b.avgCost(), a.avgCost(), b.openCost(), a.openCost(),
                        hasChild ? childId : null, childQty, childCost, fractional));
            }
            return new CorporateActionPreviewResponse(rows);
        }
    }

    /** The caller's position in the target at {@code parent}'s broker, null when they have none there. */
    private HoldingPosition childPosition(Holding parent, UUID childId) {
        return holdingRepository.findByBrokerAccountIdAndInstrumentId(parent.getBrokerAccount().getId(), childId)
                .map(investmentService::calculateHoldingPosition)
                .orElse(null);
    }

    private static BigDecimal diff(BigDecimal after, BigDecimal before) {
        return (after == null ? BigDecimal.ZERO : after).subtract(before == null ? BigDecimal.ZERO : before);
    }
}
