package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A password-protected statement no stored password opens is tied to its account by the masked
 * number the bank prints outside the PDF, then judged by that account's opt-in and window. The
 * Equitas account below opts in from 7 Oct, so its window opens at 4 Oct 00:00 IST (3 Oct 18:30 UTC).
 */
class StatementReconciliationUndecryptableTest {

    private static final String MESSAGE_ID = "msg-equitas";
    private static final String EQUITAS_BODY = "Dear MR. ADARSH JAIN A, Your e-statement for SEP-26 for customer ID XXX1277 is attached.";
    private static final Instant BEFORE_WINDOW = Instant.parse("2026-10-03T00:29:12Z");
    private static final Instant INSIDE_WINDOW = Instant.parse("2026-10-03T18:30:00Z");

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processedMessageRepository = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final StatementPersistenceService statementPersistenceService = mock(StatementPersistenceService.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private Account equitas;

    @BeforeEach
    void setUp() throws Exception {
        GmailIngestProperties properties = new GmailIngestProperties();
        service = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processedMessageRepository, properties, accountRepository, accountResolver, new TransactionMatcher(),
                new ReviewStatusManager(), statementPersistenceService, mock(CardRepository.class),
                new ParkedMessageSettler(processedMessageRepository, accountResolver, properties));

        User user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);

        equitas = new Account();
        equitas.setId(UUID.randomUUID());
        equitas.setName("Equitas");
        equitas.setIngestFromDate(LocalDate.of(2026, 10, 7));

        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf());
        when(accountRepository.findByUserIdAndHasStatementPassword(user.getId())).thenReturn(List.of());
        when(processedMessageRepository.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());
    }

    @Test
    void statementOlderThanTheNamedAccountsWindow_isSkippedNotFailed() {
        when(accountResolver.resolve("1277")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(equitas, null)));

        ReconSummary summary = service.reconcile(connection, mail(BEFORE_WINDOW, EQUITAS_BODY, "XXX1277.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(equitas);
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
        assertThat(row.getError()).isNull();
        assertThat(summary.failed()).isZero();
        assertThat(summary.failureOutcome()).isNull();
        verifyNoInteractions(statementParser, statementPersistenceService);
    }

    @Test
    void statementInsideTheWindow_stillFails_butNamesTheAccountWhosePasswordIsMissing() {
        when(accountResolver.resolve("1277")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(equitas, null)));

        ReconSummary summary = service.reconcile(connection, mail(INSIDE_WINDOW, EQUITAS_BODY, "XXX1277.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getAccount()).isSameAs(equitas);
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
        assertThat(row.getError()).isEqualTo("Encrypted statement could not be decrypted. Save the statement password on Equitas.");
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.failureOutcome()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(summary.accountLast4()).isEqualTo("1277");
    }

    @Test
    void namedAccountNotOptedIn_isParkedAsNotOptedIn() {
        equitas.setIngestFromDate(null);
        when(accountResolver.resolve("1277")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(equitas, null)));

        service.reconcile(connection, mail(BEFORE_WINDOW, EQUITAS_BODY, "XXX1277.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getAccount()).isSameAs(equitas);
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
    }

    @Test
    void numberInTheAttachmentNameAlone_isEnough() {
        when(accountResolver.resolve("1277")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(equitas, null)));

        service.reconcile(connection, mail(BEFORE_WINDOW, "Your e-statement is attached.", "XXX1277.pdf"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
    }

    @Test
    void numberMatchingNoAccount_isParkedUnresolvedWithItsDigits() {
        when(accountResolver.resolve("1277")).thenReturn(Optional.empty());

        ReconSummary summary = service.reconcile(connection, mail(BEFORE_WINDOW, EQUITAS_BODY, "XXX1277.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isNull();
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
        assertThat(summary.failureOutcome()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void numbersNamingTwoDifferentAccounts_failAsBefore() {
        Account other = new Account();
        other.setId(UUID.randomUUID());
        other.setName("Other");
        other.setIngestFromDate(LocalDate.of(2026, 10, 7));
        when(accountResolver.resolve("1277")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(equitas, null)));
        when(accountResolver.resolve("2165")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(other, null)));

        service.reconcile(connection, mail(BEFORE_WINDOW, "customer ID XXX1277, card **2165", "statement.pdf"));

        assertUndecryptableWithoutAccount();
    }

    @Test
    void noMaskedNumberAnywhere_failsAsBefore() {
        service.reconcile(connection, mail(BEFORE_WINDOW, "Your e-statement is attached. Call 18001031222.", "statement.pdf"));

        assertUndecryptableWithoutAccount();
        verifyNoInteractions(accountResolver);
    }

    // ------------------------------------------------------------------ helpers

    private void assertUndecryptableWithoutAccount() {
        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getAccount()).isNull();
        assertThat(row.getExtractedLast4()).isNull();
        assertThat(row.getError()).isEqualTo("Encrypted statement could not be decrypted with any stored password.");
    }

    private GmailProcessedMessage savedRow() {
        ArgumentCaptor<GmailProcessedMessage> captor = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processedMessageRepository).save(captor.capture());
        return captor.getValue();
    }

    private static GmailMessage mail(Instant internalDate, String body, String filename) {
        return new GmailMessage(MESSAGE_ID, internalDate, "customer-support@equitas.bank.in", "Your Bank Account Statement",
                "", body, null, List.of(new GmailAttachment("att-1", filename, "application/pdf", null)));
    }

    private static byte[] protectedPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
