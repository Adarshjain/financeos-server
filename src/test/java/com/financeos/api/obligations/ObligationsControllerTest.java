package com.financeos.api.obligations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.obligations.ObligationsService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ObligationsControllerTest {

    private ObligationsService service;
    private ObligationsController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ObligationsService.class);
        controller = new ObligationsController(service);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void passesTheUserMonthsAndAllKindsWhenKindsIsOmitted() {
        ObligationsResponse body = new ObligationsResponse(List.of());
        when(service.upcoming(userId, 3, ObligationsService.ALL_KINDS)).thenReturn(body);

        assertSame(body, controller.getUpcomingObligations(3, null));
        verify(service).upcoming(userId, 3, ObligationsService.ALL_KINDS);
    }

    @Test
    void parsesTheKindsCsv() {
        controller.getUpcomingObligations(6, "card_bill, EMI");
        verify(service).upcoming(userId, 6, Set.of("card_bill", "emi"));
    }

    @Test
    void forwardsOutOfRangeMonthsForTheServiceToClamp() {
        controller.getUpcomingObligations(40, "");
        verify(service).upcoming(userId, 40, ObligationsService.ALL_KINDS);
    }

    @Test
    void anUnknownKindIsRejectedBeforeAnyWork() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> controller.getUpcomingObligations(3, "emi,bogus"));
        assertEquals("Unknown obligation kind: bogus", ex.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void anonymousCallerGets401() {
        UserContext.clear();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getUpcomingObligations(3, null));
        assertEquals(401, ex.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
