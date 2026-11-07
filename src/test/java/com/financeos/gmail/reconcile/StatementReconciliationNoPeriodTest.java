package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import org.mockito.ArgumentCaptor;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A PDF with dated rows but no statement period (an EMI letter's schedule) is never imported as a statement. */
class StatementReconciliationNoPeriodTest {

    private static final LocalDate OCT_02 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final String MESSAGE_ID = "msg-emi";

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final StatementPersistenceService statementPersistenceService = mock(StatementPersistenceService.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private GmailMessage message;
    private Account account;

    @BeforeEach
    void setUp() throws Exception {
        service = new StatementReconciliationService(gmailEngine, statementParser, transactionRepository,
                processed, mock(AccountRepository.class),
                accountResolver, new ReviewStatusManager(), statementPersistenceService,
                mock(CardRepository.class), mock(ParkedMessageSettler.class));
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(new User());
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));
        message = new GmailMessage(MESSAGE_ID, Instant.now(), "alerts@bank", "EMI Letter", "", "", null,
                List.of(new GmailAttachment("att-1", "emi.pdf", "application/pdf", null)));
        byte[] pdf = blankPdf();
        when(gmailEngine.fetchAttachmentContent(connection, MESSAGE_ID, "att-1")).thenReturn(pdf);
        when(accountResolver.resolve(eq("XXXX1234"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        Statement statement = new Statement();
        statement.setId(UUID.randomUUID());
        when(statementPersistenceService.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any()))
                .thenReturn(Optional.of(statement));
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void parsed(LocalDate start, LocalDate end) {
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(OCT_02, new BigDecimal("-500.00"), "DEBIT", "EMI 1 OF 11", null, null),
                new ParsedStatementLine(OCT_20, new BigDecimal("-500.00"), "DEBIT", "EMI 2 OF 11", null, null)
        ), "XXXX1234", start, end, null));
    }

    private void assertSkippedAsNotAStatement(ReconSummary summary) {
        assertThat(summary.created()).isZero();
        assertThat(summary.createdTransactions()).isEmpty();
        verify(statementPersistenceService, never()).createIfNew(any(), any(), any(), any(), any(), any());
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAll(anyList());
        ArgumentCaptor<GmailProcessedMessage> ledger = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processed).save(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(ledger.getValue().getError()).isEqualTo(StatementReconciliationService.NO_PERIOD_REASON);
    }

    @Test
    void datedRowsWithNoPeriodAtAllAreNotImported() {
        parsed(null, null);

        assertSkippedAsNotAStatement(service.reconcile(connection, message));
    }

    @Test
    void aMissingPeriodEndAloneIsNotImported() {
        parsed(OCT_02, null);

        assertSkippedAsNotAStatement(service.reconcile(connection, message));
    }

    @Test
    void aMissingPeriodStartAloneIsNotImported() {
        parsed(null, OCT_20);

        assertSkippedAsNotAStatement(service.reconcile(connection, message));
    }

    // A result with both period dates still imports: StatementReconciliationClaimedRowsTest (all four tests) creates
    // transactions and links them for a result with a period.
}
