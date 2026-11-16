package com.financeos.domain.instrument.corporateaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Recording a demerger/merger asks for the target's price: it has no trade, so no trade path would. */
class CorporateActionTargetPriceEventTest {

    private static final LocalDate EX = LocalDate.of(2026, 10, 28);

    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AssetClassOverrideService overrides = mock(AssetClassOverrideService.class);
    private final CorporateActionService service = new CorporateActionService(actions, instruments,
            mock(HoldingRepository.class), mock(UserRepository.class), overrides);
    private final UUID userId = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        service.setEventPublisher(publisher);
        Instrument parent = new Instrument();
        parent.setId(parentId);
        Instrument target = new Instrument();
        target.setId(targetId);
        when(instruments.findById(parentId)).thenReturn(Optional.of(parent));
        when(instruments.findById(targetId)).thenReturn(Optional.of(target));
        when(instruments.existsById(targetId)).thenReturn(true);
        when(overrides.overridesForCurrentUser()).thenReturn(com.financeos.domain.instrument.InstrumentOverrides.NONE);
        when(actions.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void assertRefreshOfTarget() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(event.capture());
        assertThat(event.getAllValues().stream().filter(PriceRefreshEvent.class::isInstance).toList())
                .containsExactly(new PriceRefreshEvent(Set.of(targetId)));
    }

    @Test
    void creatingADemergerRequestsThePriceOfTheTarget() {
        service.createCorporateAction(parentId, new CreateCorporateActionRequest(
                CorporateActionType.demerger, 1, 1, EX, null, targetId, new BigDecimal("20"), null));

        assertRefreshOfTarget();
    }

    @Test
    void creatingAMergerRequestsThePriceOfTheTarget() {
        service.createCorporateAction(parentId, new CreateCorporateActionRequest(
                CorporateActionType.merger, 1, 2, EX, null, targetId, null, null));

        assertRefreshOfTarget();
    }

    @Test
    void aDetectedDemergerWithATargetRequestsThePriceOfTheTarget() {
        service.createDetectedWithTarget(userId, parentId, CorporateActionType.demerger, 1, 1, EX, "auto", targetId, new BigDecimal("20"));

        assertRefreshOfTarget();
    }

    @Test
    void aDetectedMergerWithATargetRequestsThePriceOfTheTarget() {
        service.createDetectedWithTarget(userId, parentId, CorporateActionType.merger, 1, 2, EX, "auto", targetId, null);

        assertRefreshOfTarget();
    }

    @Test
    void anActionWithNoTargetRequestsNothing() {
        service.createCorporateAction(parentId, new CreateCorporateActionRequest(
                CorporateActionType.split, 1, 5, EX, null, null, null, null));

        verify(publisher, never()).publishEvent(any(PriceRefreshEvent.class));
    }
}
