package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.StatementCreatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StatementCreatedListenerTest {

    private final BillNotificationService service = mock(BillNotificationService.class);
    private final StatementCreatedListener listener = new StatementCreatedListener(service);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void setsTheUserForTheCallAndClearsItAgain() {
        UUID userId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        when(service.onStatementCreated(userId, statementId)).thenAnswer(inv -> {
            assertEquals(userId, UserContext.getCurrentUserId());
            return BillNotificationService.Outcome.NONE;
        });

        listener.onStatementCreated(new StatementCreatedEvent(statementId, userId));

        verify(service).onStatementCreated(userId, statementId);
        assertNull(UserContext.getCurrentUserId());
    }

    @Test
    void keepsAnAlreadySetUserAndSwallowsFailures() {
        UUID existing = UUID.randomUUID();
        UserContext.setCurrentUserId(existing);
        UUID statementId = UUID.randomUUID();
        when(service.onStatementCreated(existing, statementId)).thenThrow(new IllegalStateException("push down"));

        listener.onStatementCreated(new StatementCreatedEvent(statementId, existing));

        assertEquals(existing, UserContext.getCurrentUserId());
    }
}
