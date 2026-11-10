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

/** The Gmail path hands the parser version and snapshot to its caller on the file handoff, never into serialised data. */
class StatementReconciliationParseRecordHandoffTest {

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

    private StatementDraft draft(LocalDate start, java.util.Map<String, Object> card) {
        return new StatementDraft("credit_card", start, OCT_20, null, null, "Bank", "XXXX1234", 1, 0, BigDecimal.TEN,
                BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.AUTO_INGEST, card, null, java.util.Set.of(), 3,
                "{\"SECRET-NARRATION\":1}");
    }

    private void parse(LocalDate start, java.util.Map<String, Object> card, LocalDate resultStart) {
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(OCT_02, new BigDecimal("-500.00"), "DEBIT", "UPI DR 498765432109 RAMESH KUMAR", null, null)),
                "XXXX1234", resultStart, OCT_20, draft(start, card)));
        when(persistence.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any())).thenReturn(Optional.of(statement));
    }

    @Test
    void aCreatedStatementsHandoffCarriesTheVersionAndSnapshot() {
        parse(OCT_02, null, OCT_02);

        StatementFileHandoff h = service.reconcile(connection, message).fileHandoff();

        assertThat(h.parserVersion()).isEqualTo(3);
        assertThat(h.parseSnapshot()).isEqualTo("{\"SECRET-NARRATION\":1}");
    }

    @Test
    void aDerivedCardPeriodChangesNeitherTheVersionNorTheSnapshot() {
        java.util.Map<String, Object> card = new java.util.HashMap<>();
        card.put("payment_due_date", LocalDate.of(2026, 11, 5));
        card.put("total_amount_due", new BigDecimal("100"));
        parse(null, card, null);

        StatementFileHandoff h = service.reconcile(connection, message).fileHandoff();

        org.mockito.ArgumentCaptor<StatementDraft> stored = org.mockito.ArgumentCaptor.forClass(StatementDraft.class);
        verify(persistence).createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), stored.capture());
        assertThat(stored.getValue().periodStart()).isNotNull();
        assertThat(stored.getValue().parserVersion()).isEqualTo(3);
        assertThat(stored.getValue().parseSnapshot()).isEqualTo("{\"SECRET-NARRATION\":1}");
        assertThat(h.parseSnapshot()).isEqualTo("{\"SECRET-NARRATION\":1}");
    }

    @Test
    void theSnapshotNeverAppearsInASerialisedOrPrintedSummaryOrHandoff() throws Exception {
        parse(OCT_02, null, OCT_02);

        ReconSummary s = service.reconcile(connection, message);

        String json = new ObjectMapper().findAndRegisterModules().configure(
                com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .writeValueAsString(new ReconSummary(s.created(), s.matched(), s.failed(), List.of(), null, null,
                        s.attachmentFilename(), null, false).withFileHandoff(s.fileHandoff()));
        assertThat(json).doesNotContain("SECRET-NARRATION").doesNotContain("parseSnapshot").doesNotContain("parserVersion");
        assertThat(s.toString()).doesNotContain("SECRET-NARRATION");
        assertThat(s.fileHandoff().toString()).doesNotContain("SECRET-NARRATION").doesNotContain("snapshot");
    }

    @Test
    void anAlreadyIngestedStatementsHandoffCarriesNoStatementIdSoNothingIsWritten() {
        parse(OCT_02, null, OCT_02);
        when(persistence.createIfNew(any(), eq(account), any(), eq(MESSAGE_ID), anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.reconcile(connection, message).fileHandoff().statementId()).isNull();
    }
}
