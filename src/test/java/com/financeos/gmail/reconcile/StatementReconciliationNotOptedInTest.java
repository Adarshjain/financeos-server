package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A statement that resolves to an account with import off: a closed account is skipped quietly (SKIPPED_BEFORE_WATERMARK),
 * an open one is parked archived as ACCOUNT_NOT_OPTED_IN. Both the readable and the undecryptable path.
 */
class StatementReconciliationNotOptedInTest {

    private static final String MESSAGE_ID = "msg-off";

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final StatementPersistenceService statementPersistenceService = mock(StatementPersistenceService.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processed, mock(AccountRepository.class), accountResolver, new ReviewStatusManager(),
                statementPersistenceService, mock(CardRepository.class),
                new ParkedMessageSettler(processed, accountResolver, new GmailIngestProperties()));
        User user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Swiggy");
        account.setIngestFromDate(null);
        when(processed.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());
    }

    private GmailProcessedMessage savedRow() {
        ArgumentCaptor<GmailProcessedMessage> captor = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processed).save(captor.capture());
        return captor.getValue();
    }

    private void readable() throws Exception {
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(pdf(false));
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(List.of(
                new ParsedStatementLine(LocalDate.of(2026, 5, 3), new BigDecimal("-10.00"), "DEBIT", "x", null, null)),
                "XXXX1234", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null));
        when(accountResolver.resolve(eq("XXXX1234"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
    }

    private void undecryptable() throws Exception {
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(pdf(true));
        when(accountResolver.resolve(eq("1277"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
    }

    private ReconSummary run(String filename) {
        GmailMessage message = new GmailMessage(MESSAGE_ID, Instant.parse("2026-06-05T04:00:00Z"), "alerts@bank.example",
                "Your statement", "", "Customer ID XXX1277", null,
                List.of(new GmailAttachment("att-1", filename, "application/pdf", null)));
        return service.reconcile(connection, message);
    }

    @Test
    void readable_closedAccountWithImportOff_isSkippedQuietly() throws Exception {
        account.setClosedOn(LocalDate.of(2026, 1, 1));
        readable();

        run("statement.pdf");

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(account);
        assertThat(row.getError()).isNull();
        assertThat(row.getArchivedAt()).isNull();
        verify(statementPersistenceService, never()).createIfNew(any(), any(), any(), any(), any(), any());
    }

    @Test
    void readable_openAccountWithImportOff_isArchivedNotOptedIn() throws Exception {
        readable();

        run("statement.pdf");

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getAccount()).isSameAs(account);
        assertThat(row.getArchivedAt()).isNotNull();
        assertThat(row.getError()).isEqualTo("Account Swiggy is not opted in for Gmail ingestion");
        verify(statementPersistenceService, never()).createIfNew(any(), any(), any(), any(), any(), any());
    }

    @Test
    void undecryptable_closedAccountWithImportOff_isSkippedQuietly() throws Exception {
        account.setClosedOn(LocalDate.of(2026, 1, 1));
        undecryptable();

        run("XXX1277.pdf");

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(account);
        assertThat(row.getError()).isNull();
        assertThat(row.getArchivedAt()).isNull();
    }

    @Test
    void undecryptable_openAccountWithImportOff_isArchivedNotOptedIn() throws Exception {
        undecryptable();

        run("XXX1277.pdf");

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getArchivedAt()).isNotNull();
        assertThat(row.getError()).isEqualTo("Account Swiggy is not opted in for Gmail ingestion");
    }

    private static byte[] pdf(boolean locked) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            if (locked) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
