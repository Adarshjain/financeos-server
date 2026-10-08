package com.financeos.domain.job.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.gmail.GmailReconnectNotificationService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SyncSummary;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GmailSyncJobHandlerTest {

    private final GmailConnectionRepository connectionRepository = mock(GmailConnectionRepository.class);
    private final GmailIngestionService ingestionService = mock(GmailIngestionService.class);
    private final GmailReconnectNotificationService reconnect = mock(GmailReconnectNotificationService.class);
    private final JobService jobService = mock(JobService.class);
    private final GmailSyncJobHandler handler = new GmailSyncJobHandler(connectionRepository, ingestionService, reconnect);

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setEmail("me@example.test");
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
    }

    private JobExecutionContext ctx(UUID user) {
        return new JobExecutionContext(UUID.randomUUID(), user, "{\"connectionId\":\"" + connectionId + "\"}", jobService, new ObjectMapper());
    }

    @Test
    void handlesGmailSyncJobs() {
        assertEquals(JobType.GMAIL_SYNC, handler.type());
    }

    @Test
    void returnsTheSyncSummaryOnSuccessWithoutTouchingTheReconnectService() throws Exception {
        SyncSummary summary = new SyncSummary(1, 1, 1, 0, 0, 0, 0, 0, 0L);
        when(ingestionService.syncConnection(connection)).thenReturn(summary);

        assertSame(summary, handler.execute(ctx(userId)));
        verify(reconnect, never()).onAuthFailure(any());
    }

    @Test
    void tokenRejectionFlagsTheMailboxAndStillFailsTheJob() {
        GmailEngineException auth = new GmailEngineException(GmailError.AUTH_ERROR, "Google rejected the refresh token (invalid_grant)");
        when(ingestionService.syncConnection(connection)).thenThrow(auth);

        GmailEngineException thrown = assertThrows(GmailEngineException.class, () -> handler.execute(ctx(userId)));

        assertSame(auth, thrown);
        verify(reconnect).onAuthFailure(connectionId);
    }

    @Test
    void otherEngineErrorsAreNotAuthFailures() {
        when(ingestionService.syncConnection(connection))
                .thenThrow(new GmailEngineException(GmailError.NETWORK_ERROR, "Network error during fetch"));

        assertThrows(GmailEngineException.class, () -> handler.execute(ctx(userId)));
        verify(reconnect, never()).onAuthFailure(any());
    }

    @Test
    void foreignConnectionIsRefusedBeforeAnySync() {
        assertThrows(ValidationException.class, () -> handler.execute(ctx(UUID.randomUUID())));
        verify(ingestionService, never()).syncConnection(any());
    }
}
