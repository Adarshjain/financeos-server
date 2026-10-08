package com.financeos.domain.job.handlers;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.gmail.GmailReconnectNotificationService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SyncSummary;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import org.springframework.stereotype.Component;

@Component
public class GmailSyncJobHandler implements JobHandler {

    private final GmailConnectionRepository connectionRepository;
    private final GmailIngestionService gmailIngestionService;
    private final GmailReconnectNotificationService reconnectNotificationService;

    public GmailSyncJobHandler(GmailConnectionRepository connectionRepository,
                               GmailIngestionService gmailIngestionService,
                               GmailReconnectNotificationService reconnectNotificationService) {
        this.connectionRepository = connectionRepository;
        this.gmailIngestionService = gmailIngestionService;
        this.reconnectNotificationService = reconnectNotificationService;
    }

    @Override
    public JobType type() {
        return JobType.GMAIL_SYNC;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        GmailSyncPayload payload = ctx.payload(GmailSyncPayload.class);
        GmailConnection connection = connectionRepository.findById(payload.connectionId())
                .orElseThrow(() -> new ResourceNotFoundException("GmailConnection", payload.connectionId()));

        if (ctx.getUserId() != null && !connection.getUser().getId().equals(ctx.getUserId())) {
            throw new ValidationException("SECURITY_MISMATCH: Gmail connection does not belong to user " + ctx.getUserId());
        }

        ctx.checkCancelled();
        try {
            return gmailIngestionService.syncConnection(connection);
        } catch (GmailEngineException e) {
            if (e.getErrorType() == GmailError.AUTH_ERROR) {
                // Google rejected the token: flag the mailbox (stops the cron) and tell the user; the job still fails.
                reconnectNotificationService.onAuthFailure(connection.getId());
            }
            throw e;
        }
    }
}
