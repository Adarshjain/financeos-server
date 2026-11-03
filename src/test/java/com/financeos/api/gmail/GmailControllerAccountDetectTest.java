package com.financeos.api.gmail;

import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.job.AccountDetectTriggers;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailSyncCursorRepository;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.oauth.GmailOAuthService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Disconnecting a mailbox clears the account-detect state and facts read from it. */
class GmailControllerAccountDetectTest {

    @Test
    void disconnectingAConnectionClearsAccountDetectForThatMailbox() {
        AuthService auth = mock(AuthService.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        AccountDetectTriggers triggers = mock(AccountDetectTriggers.class);
        GmailController controller = new GmailController(mock(GmailOAuthService.class), auth, mock(GmailIngestionService.class),
                mock(SenderAllowlistService.class), connections, mock(GmailSyncCursorRepository.class),
                mock(GmailProcessedMessageRepository.class), mock(GmailBackfillDemandRepository.class), mock(AccountRepository.class),
                mock(TransactionRepository.class), mock(JobService.class), mock(GmailIngestProperties.class),
                mock(AccountIdentifierService.class), triggers);
        User user = new User();
        user.setId(UUID.randomUUID());
        when(auth.getCurrentUser()).thenReturn(user);
        GmailConnection c = new GmailConnection();
        c.setId(UUID.randomUUID());
        c.setUser(user);
        c.setIsConnected(true);
        when(connections.findById(c.getId())).thenReturn(Optional.of(c));

        controller.disconnectConnection(c.getId());

        assertThat(c.getIsConnected()).isFalse();
        verify(triggers).onDisconnected(user.getId(), c.getId());
    }
}
