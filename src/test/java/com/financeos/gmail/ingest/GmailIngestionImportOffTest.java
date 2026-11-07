package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.attention.IgnoredNumberRules;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alert mail whose account has Gmail import off: a closed account's mail is skipped quietly (no notice); an open account's
 * is parked and archived, so the app shows one entry per account instead of one row per email. Unresolved mail with a number
 * the user ignores is archived right after the batch.
 */
class GmailIngestionImportOffTest {

    private GmailEngine gmailEngine;
    private AccountResolver accountResolver;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailIngestionService service;
    private IgnoredNumberRules ignoredNumberRules;

    private GmailConnection connection;
    private UUID userId;
    private GmailProcessedMessage row;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        accountResolver = mock(AccountResolver.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        GmailSyncCursorService cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        GmailBackfillDemandRepository backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        GmailIngestProperties ingestProperties = mock(GmailIngestProperties.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ignoredNumberRules = mock(IgnoredNumberRules.class);

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                mock(GmailTransactionWriter.class), geminiExtractor, processedMessageRepository, backfillDemandRepository,
                ingestProperties, mock(StatementReconciliationService.class), mock(CategorizationService.class),
                mock(TransactionRepository.class), transactionManager, mock(ParkedMessageSettler.class));
        ReflectionTestUtils.setField(service, "ignoredNumberRules", ignoredNumberRules);

        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");

        GmailSender sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress("alerts@bank.com");
        sender.setEnabled(true);
        when(senderRepository.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of(sender));
        GmailSyncCursor cursor = new GmailSyncCursor();
        cursor.setUser(user);
        cursor.setConnection(connection);
        cursor.setSender(sender);
        cursor.setLastListedAt(Instant.now().minusSeconds(86400));
        cursor.setEarliestCoveredAt(Instant.now().minusSeconds(86400));
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursor));
        when(ingestProperties.getProcessBudget()).thenReturn(100);
        when(ingestProperties.getBackfillProcessBudget()).thenReturn(500);
        when(ingestProperties.getOverlapLapMinutes()).thenReturn(15);
        when(ingestProperties.getStaleProcessingMinutes()).thenReturn(60);
        when(ingestProperties.getRetryMaxAttempts()).thenReturn(5);
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.empty());

        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("msg-1"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("msg-1");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        GmailMessage details = new GmailMessage("msg-1", Instant.now(), "alerts@bank.com", "Alert", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(connection, "msg-1")).thenReturn(details);
        when(geminiExtractor.extract(details)).thenReturn(new GeminiExtractionResult(true, new java.math.BigDecimal("500.00"), "INR",
                "debit", LocalDate.now(), "Store", "4321", 0.95, true, null));
    }

    private Account account(LocalDate closedOn) {
        Account account = new Account("Swiggy", AccountType.credit_card);
        account.setIngestFromDate(null);
        account.setClosedOn(closedOn);
        when(accountResolver.resolve(eq("4321"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        return account;
    }

    @Test
    void closedAccountWithImportOff_isSkippedWithTheAccountAndNoNotice() {
        Account account = account(LocalDate.of(2026, 4, 30));

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(account);
        assertThat(row.getError()).isNull();
        assertThat(row.getArchivedAt()).isNull();
    }

    @Test
    void openAccountWithImportOff_isParkedAndArchived() {
        account(null);

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getArchivedAt()).isNotNull();
        verify(ignoredNumberRules, never()).archiveIfIgnored(any(), any());
    }

    @Test
    void accountClosingInTheFuture_isStillOpenSoItIsParkedAndArchived() {
        account(LocalDate.now().plusDays(30));

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
    }

    @Test
    void unresolvedMail_isHandedToTheIgnoredNumberRulesAfterTheBatch() {
        when(accountResolver.resolve(eq("4321"), any(), any())).thenReturn(Optional.empty());

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        verify(ignoredNumberRules).archiveIfIgnored(eq(userId), eq(List.of(row.getId())));
    }
}
