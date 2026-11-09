package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
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
import java.util.Map;
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

/** Old card bills print only a statement date: imported with a derived one-month period; anything else without a period stays skipped. */
class StatementReconciliationStatementDateOnlyTest {

    private static final LocalDate OCT_02 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final String MESSAGE_ID = "msg-card";

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

    private static final LocalDate AUG_17 = LocalDate.of(2018, 8, 17);

    private void parsed(String type, Map<String, Object> cardFields) {
        StatementDraft draft = new StatementDraft(type, null, AUG_17, null, null, null, "XXXX1234", 2, 0, null, null,
                "heuristic", null, null, null, cardFields);
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(LocalDate.of(2018, 8, 2), new BigDecimal("-500.00"), "DEBIT", "SHOP 1", null, null),
                new ParsedStatementLine(LocalDate.of(2018, 8, 5), new BigDecimal("-500.00"), "DEBIT", "SHOP 2", null, null)
        ), "XXXX1234", null, AUG_17, draft));
    }

    private void assertSkipped(ReconSummary summary) {
        assertThat(summary.created()).isZero();
        verify(statementPersistenceService, never()).createIfNew(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<GmailProcessedMessage> ledger = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processed).save(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(ledger.getValue().getError()).isEqualTo(StatementReconciliationService.NO_PERIOD_REASON);
    }

    @Test
    void cardBillWithDueDateAndTotalDueGetsDerivedMonthlyPeriod() {
        parsed("credit_card", Map.of("payment_due_date", LocalDate.of(2018, 9, 4), "total_amount_due", new BigDecimal("1000.00")));

        service.reconcile(connection, message);

        ArgumentCaptor<StatementDraft> draft = ArgumentCaptor.forClass(StatementDraft.class);
        verify(statementPersistenceService).createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), draft.capture());
        assertThat(draft.getValue().periodStart()).isEqualTo(LocalDate.of(2018, 7, 18));
        assertThat(draft.getValue().periodEnd()).isEqualTo(AUG_17);
    }

    @Test
    void cardWithoutDueDateAndTotalDueStaysSkipped() {
        parsed("credit_card", Map.of("minimum_amount_due", new BigDecimal("50.00")));

        assertSkipped(service.reconcile(connection, message));
    }

    @Test
    void bankAccountWithOnlyPeriodEndStaysSkipped() {
        parsed("bank_account", Map.of("payment_due_date", LocalDate.of(2018, 9, 4), "total_amount_due", new BigDecimal("1000.00")));

        assertSkipped(service.reconcile(connection, message));
    }
}
