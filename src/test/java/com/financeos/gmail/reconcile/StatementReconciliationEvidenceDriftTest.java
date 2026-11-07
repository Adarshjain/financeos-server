package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A readable statement whose number resolves to nothing: the evidence in its own text, and the misread-number self-check. Synthetic data. */
class StatementReconciliationEvidenceDriftTest {

    private static final String MESSAGE_ID = "msg-1";
    private static final String SENDER = "statements@hdfcbank.net";

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processedRepo = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final AccountIdentifierRepository identifierRepository = mock(AccountIdentifierRepository.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private User user;
    private Account savings;
    private Account salary;

    @BeforeEach
    void setUp() {
        AccountResolver resolver = new AccountResolver(accountRepository, cardRepository, identifierRepository);
        service = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processedRepo, accountRepository, resolver, new ReviewStatusManager(), mock(StatementPersistenceService.class),
                cardRepository, new ParkedMessageSettler(processedRepo, resolver, new GmailIngestProperties()));
        service.setIdentifierRepository(identifierRepository);
        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        when(processedRepo.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());
        when(accountRepository.findByUserIdAndHasStatementPassword(user.getId())).thenReturn(List.of());

        savings = account("Test Savings", "hdfc", "0001");
        salary = account("Test Salary", "hdfc", "0002");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(savings, salary));
        when(accountRepository.findAll()).thenReturn(List.of(savings, salary));
        when(cardRepository.findAll()).thenReturn(List.of());
        when(identifierRepository.findAll()).thenReturn(List.of());
        when(identifierRepository.findByUserId(user.getId())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ evidence in the statement text

    @Test
    void aSavedFullAccountNumberInTheTextResolvesIt() throws Exception {
        identifier(salary, "50100000000002", AccountIdentifierKind.ACCOUNT_NUMBER);
        readable("314842", "Account Number : 50100000000002 TEST PERSON");

        service.reconcile(connection, mail());

        assertThat(savedRow().getStatus()).isNotEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(savedRow().getError()).doesNotStartWith("Failed to resolve");
    }

    @Test
    void aCustomerIdAloneIsNotEvidence() throws Exception {
        identifier(savings, "7770009", AccountIdentifierKind.CUSTOMER_ID);
        readable("314842", "Customer ID : 7770009 Statement of Accounts");

        service.reconcile(connection, mail());

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isNull();
    }

    @Test
    void twoAccountsNamedInTheTextStayUnresolved() throws Exception {
        identifier(savings, "50100000000001", AccountIdentifierKind.ACCOUNT_NUMBER);
        identifier(salary, "50100000000002", AccountIdentifierKind.ACCOUNT_NUMBER);
        readable("314842", "Account Number : 50100000000001\nLinked 50100000000002");

        service.reconcile(connection, mail());

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractedLast4()).isEqualTo("4842");
    }

    @Test
    void noEvidenceInTheTextStaysUnresolvedEvenWithOneAccountAtTheBank() throws Exception {
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(savings));
        identifier(savings, "50100000000001", AccountIdentifierKind.ACCOUNT_NUMBER);
        readable("314842", "Statement of Accounts\nINR SAVINGS ACCOUNTS 314842.12 CR");

        service.reconcile(connection, mail());

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isNull();
    }

    @Test
    void moneyAndLongerNumbersContainingTheNumberAreNotEvidence() throws Exception {
        identifier(savings, "50100000000001", AccountIdentifierKind.ACCOUNT_NUMBER);
        identifier(salary, "12345", AccountIdentifierKind.OTHER);
        readable("314842", "Bal 50100000000001.25 and 1,50100000000001 and 950100000000001 and 501000000000019");

        service.reconcile(connection, mail());

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    // ------------------------------------------------------------------ drift self-check

    @Test
    void priorReconciledStatementsFromTheSenderMakeAStrayNumberADriftNotANewAccount() throws Exception {
        when(processedRepo.countReconciledStatementsBySender(user.getId(), SENDER, MESSAGE_ID)).thenReturn(List.of(count(savings, 3)));
        when(accountRepository.findByIdAndUser_Id(savings.getId(), user.getId())).thenReturn(Optional.of(savings));
        readable("314842", "nothing useful");

        ReconSummary summary = service.reconcile(connection, mail());

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractedLast4()).isEqualTo("4842");
        assertThat(row.getError()).contains("••4842").contains("Test Savings").contains("may have been misread");
        assertThat(summary.accountLast4()).isEqualTo("4842");
        assertThat(summary.failureOutcome()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
    }

    @Test
    void noHistoryLeavesTheUnknownNumberAsItWas() throws Exception {
        when(processedRepo.countReconciledStatementsBySender(user.getId(), SENDER, MESSAGE_ID)).thenReturn(List.of());
        readable("314842", "nothing useful");

        service.reconcile(connection, mail());

        GmailProcessedMessage row = savedRow();
        assertThat(row.getExtractedLast4()).isEqualTo("4842");
        assertThat(row.getError()).startsWith("Failed to resolve account");
    }

    @Test
    void aSenderThatServedSeveralAccountsOrOnlyOnceIsNoHistory() throws Exception {
        when(processedRepo.countReconciledStatementsBySender(user.getId(), SENDER, MESSAGE_ID))
                .thenReturn(List.of(count(savings, 3), count(salary, 2)));
        readable("314842", "nothing useful");
        service.reconcile(connection, mail());
        assertThat(savedRow().getExtractedLast4()).isEqualTo("4842");

        org.mockito.Mockito.clearInvocations(processedRepo);
        when(processedRepo.countReconciledStatementsBySender(user.getId(), SENDER, MESSAGE_ID)).thenReturn(List.of(count(savings, 1)));
        service.reconcile(connection, mail());
        assertThat(savedRow().getExtractedLast4()).isEqualTo("4842");
    }

    // ------------------------------------------------------------------ helpers

    private void readable(String accountNumber, String text) throws Exception {
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(plainPdf());
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 5, 3), new BigDecimal("10.00"), "DEBIT", "x", null, null)),
                accountNumber, LocalDate.of(2026, 4, 19), LocalDate.of(2026, 5, 18), null).withSummaryText(text));
    }

    private Account account(String name, String bank, String last4) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setBank(bank);
        a.setType(AccountType.bank_account);
        a.setBankDetails(new AccountBankDetails(a, BigDecimal.ZERO, last4));
        return a; // no ingest date: a resolved account parks as not opted in, which is enough to tell resolved from unresolved
    }

    private void identifier(Account a, String value, AccountIdentifierKind kind) {
        List<AccountIdentifier> all = new java.util.ArrayList<>(identifierRepository.findByUserId(user.getId()));
        all.add(new AccountIdentifier(UUID.randomUUID(), user, a, value, kind, Instant.now()));
        when(identifierRepository.findByUserId(user.getId())).thenReturn(all);
    }

    private static GmailProcessedMessageRepository.SenderAccountCount count(Account a, long total) {
        return new GmailProcessedMessageRepository.SenderAccountCount() {
            public UUID getAccountId() { return a.getId(); }
            public long getTotal() { return total; }
        };
    }

    private GmailProcessedMessage savedRow() {
        ArgumentCaptor<GmailProcessedMessage> captor = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processedRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static GmailMessage mail() {
        return new GmailMessage(MESSAGE_ID, Instant.parse("2026-05-19T04:30:00Z"), "HDFC Bank <" + SENDER + ">",
                "Combined Email Statement", "", "Your statement is attached.", null,
                List.of(new GmailAttachment("att-1", "stmt.pdf", "application/pdf", null)));
    }

    private static byte[] plainPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }
}
