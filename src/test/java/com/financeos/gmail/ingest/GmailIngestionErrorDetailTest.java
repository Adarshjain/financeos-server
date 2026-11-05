package com.financeos.gmail.ingest;

import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A failing message keeps the stack of the original exception on its ledger row, next to the unchanged root-cause message. */
class GmailIngestionErrorDetailTest {

    private GmailIngestionService service;
    private Method applyFailure;

    @BeforeEach
    void setUp() throws Exception {
        GmailIngestProperties props = mock(GmailIngestProperties.class);
        when(props.getRetryMaxAttempts()).thenReturn(5);
        service = new GmailIngestionService(mock(GmailEngine.class), mock(GmailSyncCursorService.class),
                mock(GmailSenderRepository.class), mock(AccountResolver.class), mock(GmailTransactionWriter.class),
                mock(GeminiExtractor.class), mock(GmailProcessedMessageRepository.class), mock(GmailBackfillDemandRepository.class),
                props, mock(StatementReconciliationService.class), mock(CategorizationService.class),
                mock(TransactionRepository.class), mock(PlatformTransactionManager.class), mock(ParkedMessageSettler.class));
        applyFailure = GmailIngestionService.class.getDeclaredMethod("applyFailure", GmailProcessedMessage.class, Throwable.class, String.class);
        applyFailure.setAccessible(true);
    }

    @Test
    void storesTheOriginalExceptionsStackAndKeepsTheRootMessage() throws Exception {
        GmailProcessedMessage gpm = new GmailProcessedMessage();
        Exception original = new IllegalStateException("wrapper", new IllegalArgumentException("root cause"));

        applyFailure.invoke(service, gpm, original, "root cause");

        assertThat(gpm.getError()).isEqualTo("root cause");
        assertThat(gpm.getErrorDetail()).contains("java.lang.IllegalStateException: wrapper")
                .contains("Caused by: java.lang.IllegalArgumentException: root cause")
                .contains("GmailIngestionErrorDetailTest");
        assertThat(gpm.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(gpm.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void truncatesAHugeStackTo16000Chars() throws Exception {
        GmailProcessedMessage gpm = new GmailProcessedMessage();

        applyFailure.invoke(service, gpm, new RuntimeException("y".repeat(40_000)), "short");

        assertThat(gpm.getErrorDetail()).hasSize(16000);
        assertThat(gpm.getError()).isEqualTo("short");
    }

    @Test
    void skippedByAdminIsCountedAsSkippedAndRanksLast() throws Exception {
        Method rank = GmailIngestionService.class.getDeclaredMethod("displayRank", GmailProcessedStatus.class);
        rank.setAccessible(true);
        int failed = (int) rank.invoke(null, GmailProcessedStatus.FAILED_PERMANENT);
        int skipped = (int) rank.invoke(null, GmailProcessedStatus.SKIPPED_BY_ADMIN);
        int other = (int) rank.invoke(null, GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(skipped).isGreaterThan(failed).isGreaterThanOrEqualTo(other);
    }
}
