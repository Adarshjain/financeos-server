package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.match.MatchFixture;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The Gmail path's import gate, and the handoff of the statement's original file to the caller. */
class StatementReconciliationImportGateTest {

    private static final LocalDate OCT_02 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_20 = LocalDate.of(2026, 10, 20);
    private static final String MESSAGE_ID = "msg-gate";
    private byte[] pdf;

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final MatchFixture fixture = new MatchFixture(transactionRepository);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private GmailMessage message;
    private Account account;
    private Statement statement;

    private static byte[] blankPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        pdf = blankPdf();
        service = new StatementReconciliationService(gmailEngine, statementParser, transactionRepository, processed,
                mock(AccountRepository.class), accountResolver, new ReviewStatusManager(), persistence,
                mock(CardRepository.class), mock(ParkedMessageSettler.class));
        service.setBatchMatcher(fixture.matcher);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        User user = new User();
        user.setId(UUID.randomUUID());
        connection.setUser(user);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setType(AccountType.credit_card);
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));
        message = new GmailMessage(MESSAGE_ID, Instant.now(), "alerts@bank", "Statement", "", "", null,
                List.of(new GmailAttachment("att-1", "statement.pdf", "application/pdf", null)));
        when(gmailEngine.fetchAttachmentContent(connection, MESSAGE_ID, "att-1")).thenReturn(pdf);
        when(accountResolver.resolve(eq("XXXX1234"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setPeriodStart(OCT_02);
        statement.setPeriodEnd(OCT_20);
    }

    private StatementDraft draft(StatementVerdict verdict, String mode, Boolean checksumOk) {
        return new StatementDraft("credit_card", OCT_02, OCT_20, null, null, "Bank", "XXXX1234", 2, 0, BigDecimal.TEN,
                BigDecimal.ZERO, mode, null, checksumOk, verdict, null, null);
    }

    private void parsed(StatementDraft draft) {
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(OCT_02, new BigDecimal("-500.00"), "DEBIT", "UPI DR 498765432109 RAMESH KUMAR", null, null),
                new ParsedStatementLine(OCT_20, new BigDecimal("-90.00"), "DEBIT", "UPI DR 411111111111 TEA STALL", null, null)
        ), "XXXX1234", OCT_02, OCT_20, draft));
    }

    private void created() {
        when(persistence.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any())).thenReturn(Optional.of(statement));
    }

    // ---- the gate

    private void assertBlocked(ReconSummary s) {
        assertThat(s.failed()).isEqualTo(1);
        assertThat(s.failureOutcome()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(s.failureReason()).isEqualTo("Statement rows don't add up to its own totals; nothing imported");
        assertThat(s.attachmentFilename()).isEqualTo("statement.pdf");
        assertThat(s.created()).isZero();
        assertThat(s.fileHandoff()).isNull();
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
        verify(transactionRepository, never()).save(any());
        ArgumentCaptor<GmailProcessedMessage> ledger = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processed).save(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(ledger.getValue().getError()).isEqualTo("Statement rows don't add up to its own totals; nothing imported");
    }

    @Test
    void rejectedWithAFailedChecksumIsFailedPermanentAndImportsNothing() {
        parsed(draft(StatementVerdict.REJECTED, "opening_closing", Boolean.FALSE));

        assertBlocked(service.reconcile(connection, message));
    }

    @Test
    void rejectedInBalanceChainModeIsFailedPermanentAndImportsNothing() {
        parsed(draft(StatementVerdict.REJECTED, StatementParser.MODE_BALANCE_CHAIN, null));

        assertBlocked(service.reconcile(connection, message));
    }

    @Test
    void aRejectedCardParseWithNothingToCheckAgainstStillImports() {
        // The shape the real parser gives a card statement with no readable summary: REJECTED, no checksum, card check FALSE.
        parsed(new StatementDraft("credit_card", OCT_02, OCT_20, null, null, "Bank", "XXXX1234", 2, 0, BigDecimal.TEN,
                BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.REJECTED, null, Boolean.FALSE));
        created();

        ReconSummary s = service.reconcile(connection, message);

        assertThat(s.failed()).isZero();
        assertThat(s.created()).isEqualTo(2);
    }

    @Test
    void rejectedWithNothingToCheckStillImportsAsToday() {
        parsed(draft(StatementVerdict.REJECTED, "heuristic", null));
        created();

        ReconSummary s = service.reconcile(connection, message);

        assertThat(s.failed()).isZero();
        assertThat(s.created()).isEqualTo(2);
        verify(persistence).createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any());
    }

    @Test
    void needsReviewStillImports() {
        parsed(draft(StatementVerdict.NEEDS_REVIEW, "balance_chain", Boolean.FALSE));
        created();

        assertThat(service.reconcile(connection, message).created()).isEqualTo(2);
    }

    // ---- the file handoff

    @Test
    void aCreatedStatementHandsItsFileToTheCaller() {
        parsed(draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        created();

        ReconSummary s = service.reconcile(connection, message);

        StatementFileHandoff h = s.fileHandoff();
        assertThat(h.statementId()).isEqualTo(statement.getId());
        assertThat(h.userId()).isEqualTo(connection.getUser().getId());
        assertThat(h.accountId()).isEqualTo(account.getId());
        assertThat(h.sha256()).isEqualTo(StatementPersistenceService.sha256Hex(pdf));
        assertThat(h.bytes()).isEqualTo(pdf);
        assertThat(h.originalName()).isEqualTo("statement.pdf");
    }

    @Test
    void aCreatedStatementWhoseLinesAreAllBeforeTheWatermarkStillHandsItsFileOver() {
        account.setIngestFromDate(LocalDate.of(2026, 12, 1));
        parsed(draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        created();

        ReconSummary s = service.reconcile(connection, message);

        assertThat(s.created()).isZero();
        assertThat(s.fileHandoff().statementId()).isEqualTo(statement.getId());
    }

    @Test
    void anAlreadyIngestedStatementHandsOverForBackFillWithNoStatementId() {
        parsed(draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        when(persistence.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any())).thenReturn(Optional.empty());

        ReconSummary s = service.reconcile(connection, message);

        assertThat(s.fileHandoff().statementId()).isNull();
        assertThat(s.fileHandoff().accountId()).isEqualTo(account.getId());
        assertThat(s.fileHandoff().sha256()).isEqualTo(StatementPersistenceService.sha256Hex(pdf));
    }

    @Test
    void aNotAStatementOutcomeHandsOverNothing() {
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(OCT_02, new BigDecimal("-500.00"), "DEBIT", "EMI", null, null)), "XXXX1234", null, null, null));

        assertThat(service.reconcile(connection, message).fileHandoff()).isNull();
    }

    @Test
    void theFileBytesNeverAppearInASerialisedOrPrintedSummary() throws Exception {
        parsed(draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        created();

        ReconSummary s = service.reconcile(connection, message);

        String json = new ObjectMapper().findAndRegisterModules().configure(
                com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .writerWithDefaultPrettyPrinter().writeValueAsString(
                        new ReconSummary(s.created(), s.matched(), s.failed(), List.of(), null, null, s.attachmentFilename(), null, false)
                                .withFileHandoff(s.fileHandoff()));
        assertThat(json).doesNotContain("fileHandoff").doesNotContain("bytes").doesNotContain("sha256");
        assertThat(s.toString()).doesNotContain("[B@").doesNotContain("sha256");
        assertThat(s.fileHandoff().toString()).doesNotContain("[B@").doesNotContain(java.util.Arrays.toString(pdf));
    }
}
