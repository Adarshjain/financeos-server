package com.financeos.gmail.ingest;

import com.financeos.domain.categorization.CategorizationService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailSyncState;
import com.financeos.gmail.history.SyncStateService;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailFetchResult;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.ReconSummary;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GmailIngestionServiceTest {

    private GmailEngine gmailEngine;
    private SyncStateService syncStateService;
    private GmailSenderRepository gmailSenderRepository;
    private AccountResolver accountResolver;
    private GmailTransactionWriter gmailTransactionWriter;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailIngestProperties ingestProperties;
    private StatementReconciliationService statementReconciliationService;
    private CategorizationService categorizationService;
    private GmailIngestionService gmailIngestionService;

    private GmailConnection connection;
    private UUID userId;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        syncStateService = mock(SyncStateService.class);
        gmailSenderRepository = mock(GmailSenderRepository.class);
        accountResolver = mock(AccountResolver.class);
        gmailTransactionWriter = mock(GmailTransactionWriter.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        ingestProperties = mock(GmailIngestProperties.class);
        statementReconciliationService = mock(StatementReconciliationService.class);
        categorizationService = mock(CategorizationService.class);

        gmailIngestionService = new GmailIngestionService(
                gmailEngine,
                syncStateService,
                gmailSenderRepository,
                accountResolver,
                gmailTransactionWriter,
                geminiExtractor,
                processedMessageRepository,
                ingestProperties,
                statementReconciliationService,
                categorizationService
        );

        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");

        GmailSender sender = new GmailSender();
        sender.setSenderAddress("alerts@bank.com");
        when(gmailSenderRepository.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of(sender));
        when(ingestProperties.getFirstBackfillDays()).thenReturn(30);

        GmailSyncState syncState = new GmailSyncState("hist-1", Instant.now());
        when(syncStateService.getSyncState(any())).thenReturn(syncState);
    }


    @Test
    void testAccountUnresolvedAndExtractionFailedAndReconFailure() {
        GmailMessage msg1 = new GmailMessage("msg-1", Instant.now(), "alerts@bank.com", "Alert 1", "snippet 1", "body 1", null, null);
        GmailMessage msg2 = new GmailMessage("msg-2", Instant.now(), "alerts@bank.com", "Alert 2", "snippet 2", "body 2", null, null);
        GmailMessage msg3 = new GmailMessage("msg-3", Instant.now(), "alerts@bank.com", "Statement Email", "snippet 3", "statement", null, List.of(new GmailAttachment("att-1", "stmt.pdf", "application/pdf", new byte[0])));
        GmailMessage msg4 = new GmailMessage("msg-4", Instant.now(), "alerts@bank.com", "Already processed", "snippet 4", "body 4", null, null);

        GmailFetchResult fetchResult = new GmailFetchResult(List.of(msg1, msg2, msg3, msg4), new GmailSyncState("hist-2", Instant.now()));
        when(gmailEngine.fetch(any(), any())).thenReturn(fetchResult);

        // msg4 is already processed
        when(processedMessageRepository.findByConnectionIdAndGmailMessageId(connection.getId(), "msg-4"))
                .thenReturn(Optional.of(new GmailProcessedMessage()));

        // msg1: extraction failed
        GeminiExtractionResult extFailed = GeminiExtractionResult.failure("Gemini timeout");
        when(geminiExtractor.extract(msg1)).thenReturn(extFailed);

        // msg2: account unresolved (extract success, last4 "9999", accountResolver returns empty)
        GeminiExtractionResult extAccUnresolved = new GeminiExtractionResult(true, new java.math.BigDecimal("10.00"), "INR", "debit", LocalDate.now(), "Desc", "9999", 0.9, true, null);
        when(geminiExtractor.extract(msg2)).thenReturn(extAccUnresolved);
        when(accountResolver.resolve("9999")).thenReturn(Optional.empty());

        // msg3: statement path with PARSE_FAILED
        ReconSummary parseFailedRecon = new ReconSummary(0, 0, 1, List.of(), SyncSummary.Outcome.PARSE_FAILED, "Statement parse failed: Invalid format", "stmt.pdf", null, false);
        when(statementReconciliationService.reconcile(connection, msg3)).thenReturn(parseFailedRecon);

        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        assertThat(summary.fetched()).isEqualTo(4);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.alreadyProcessed()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(3);
        assertThat(summary.attention()).hasSize(3);

        assertThat(summary.attention().get(0).outcome()).isEqualTo(SyncSummary.Outcome.EXTRACTION_FAILED);
        assertThat(summary.attention().get(0).reason()).isEqualTo("Gemini timeout");

        assertThat(summary.attention().get(1).outcome()).isEqualTo(SyncSummary.Outcome.ACCOUNT_UNRESOLVED);
        assertThat(summary.attention().get(1).accountLast4()).isEqualTo("9999");

        assertThat(summary.attention().get(2).outcome()).isEqualTo(SyncSummary.Outcome.PARSE_FAILED);
        assertThat(summary.attention().get(2).attachmentFilename()).isEqualTo("stmt.pdf");
    }

    @Test
    void testAttentionCappingAt50() {
        List<GmailMessage> messages = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            messages.add(new GmailMessage("msg-" + i, Instant.now(), "alerts@bank.com", "Alert " + i, "snippet", "body", null, null));
        }

        GmailFetchResult fetchResult = new GmailFetchResult(messages, new GmailSyncState("hist-2", Instant.now()));
        when(gmailEngine.fetch(any(), any())).thenReturn(fetchResult);

        GeminiExtractionResult extFailed = GeminiExtractionResult.failure("Extraction error");
        when(geminiExtractor.extract(any())).thenReturn(extFailed);

        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        assertThat(summary.failed()).isEqualTo(55);
        assertThat(summary.attention()).hasSize(50);
        assertThat(summary.attentionTruncated()).isEqualTo(5);
    }

}
