package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.statement.StatementCoverage;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HSBC card statement mails (locked, no month in the subject, accounts with no saved digits) and masked-number
 * resolution of readable statements: the owner's real dates, synthetic accounts.
 */
class StatementReconciliationHsbcResolveTest {

    private static final String MESSAGE_ID = "msg-1";
    private static final String HSBC = "alerts@mail.hsbc.co.in";

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processedRepo = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final StatementCoverage coverage = mock(StatementCoverage.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final AccountIdentifierRepository identifierRepository = mock(AccountIdentifierRepository.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private User user;
    private Account premier;
    private Account live;

    @BeforeEach
    void setUp() {
        AccountResolver resolver = new AccountResolver(accountRepository, cardRepository, identifierRepository);
        service = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processedRepo, accountRepository, resolver, new ReviewStatusManager(), persistence, cardRepository,
                new ParkedMessageSettler(processedRepo, resolver, new GmailIngestProperties()));
        service.setStatementCoverage(coverage);
        service.setIdentifierRepository(identifierRepository);
        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        when(accountRepository.findByUserIdAndHasStatementPassword(user.getId())).thenReturn(List.of());
        when(processedRepo.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());

        premier = account("Premier", "hsbc", AccountType.credit_card, LocalDate.of(2026, 9, 17));
        live = account("Live+", "hsbc", AccountType.credit_card, LocalDate.of(2026, 9, 12));
        primaryCard(premier, "1111");
        primaryCard(live, "2222");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(premier, live));
        when(coverage.statements(premier.getId())).thenReturn(periods(
                "2026-03-19", "2026-04-18", "2026-04-19", "2026-05-18", "2026-05-19", "2026-06-18", "2026-06-19", "2026-07-18",
                "2026-07-19", "2026-08-18", "2026-08-19", "2026-09-18", "2026-09-19", "2026-10-18"));
        // Live+ changed its cycle in July: a gap between the 07-08 and the 08-18 statements.
        when(coverage.statements(live.getId())).thenReturn(periods(
                "2026-06-09", "2026-07-08", "2026-07-20", "2026-08-18", "2026-08-19", "2026-09-18", "2026-09-19", "2026-10-18"));
    }

    @Test
    void premierMailsBeforeItsWindow_areSkippedBeforeWatermark() throws Exception {
        for (String sent : List.of("2026-04-19", "2026-05-18", "2026-06-18", "2026-07-18", "2026-08-18")) {
            assertSkipped(sent, "Your HSBC Credit Card statement (HSBC PREMIER)", premier, GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        }
    }

    @Test
    void liveMailsBeforeItsWindow_areSkippedBeforeWatermark() throws Exception {
        for (String sent : List.of("2026-07-08", "2026-08-18")) {
            assertSkipped(sent, "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", live, GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        }
    }

    @Test
    void mailsOfSeptember18_areSkippedAsDuplicates_evenAcrossACycleGap() throws Exception {
        assertSkipped("2026-09-18", "Your HSBC Credit Card statement (HSBC PREMIER)", premier, GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertSkipped("2026-09-18", "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", live, GmailProcessedStatus.SKIPPED_DUPLICATE);
    }

    @Test
    void anInWindowMailWhosePeriodIsNotHeld_isStillOpened() throws Exception {
        when(coverage.statements(premier.getId())).thenReturn(periods("2026-07-19", "2026-08-18"));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf());

        service.reconcile(connection, mail("2026-09-18", "Your HSBC Credit Card statement (HSBC PREMIER)", "statement.pdf", "Your statement is attached."));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT); // not skipped: asks for the password
        assertThat(row.getAccount()).isSameAs(premier);
    }

    @Test
    void readableEternaStatement_resolvesAMaskedNumberToItsClosedCard() throws Exception {
        Account eterna = account("Eterna", "bank_of_baroda", AccountType.credit_card, LocalDate.of(2025, 12, 1));
        Card open = card(eterna, "2358", null);
        Card closed = card(eterna, "5554", LocalDate.of(2026, 8, 31));
        when(accountRepository.findAll()).thenReturn(List.of(eterna));
        when(cardRepository.findAll()).thenReturn(List.of(open, closed));
        when(identifierRepository.findAll()).thenReturn(List.of());
        readable("453157XXXXXX5554");

        service.reconcile(connection, mail("2026-05-19", "E-statement for your BOBCARD VISA ETERNA credit card ending 5554", "e.pdf",
                "Your statement is attached.", "alerts@bobcard.co.in"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isNotEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isSameAs(eterna);
    }

    @Test
    void readableEquitasStatement_resolvesLast4CollisionBySenderBank() throws Exception {
        Account equitas = account("Elite Lite", "equitas", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        bankLast4(equitas, "2183");
        Account jupiter = account("Jupiter Edge+", "csb", AccountType.bank_account, null);
        bankLast4(jupiter, "2183");
        when(accountRepository.findAll()).thenReturn(List.of(equitas, jupiter));
        when(cardRepository.findAll()).thenReturn(List.of());
        when(identifierRepository.findAll()).thenReturn(List.of());
        readable("100008572183");

        service.reconcile(connection, mail("2026-11-04", "Your Elite Lite Bank Account Statement OCT-26", "s.pdf",
                "Your statement is attached.", "statements@equitas.bank.in"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isNotEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isSameAs(equitas);
    }

    @Test
    void readableStatement_staysUnresolvedWhenBothCollidingAccountsAreAtTheSenderBank() throws Exception {
        Account first = account("Elite Lite", "equitas", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        bankLast4(first, "2183");
        Account second = account("Equitas Savings", "equitas", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        bankLast4(second, "2183");
        when(accountRepository.findAll()).thenReturn(List.of(first, second));
        when(cardRepository.findAll()).thenReturn(List.of());
        when(identifierRepository.findAll()).thenReturn(List.of());
        readable("100008572183");

        service.reconcile(connection, mail("2026-11-04", "Your Bank Account Statement OCT-26", "s.pdf",
                "Your statement is attached.", "statements@equitas.bank.in"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void readable(String accountNumber) throws Exception {
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(plainPdf());
        when(statementParser.parse(any(), any())).thenReturn(StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 5, 3), new BigDecimal("10.00"), "DEBIT", "x", null, null)),
                accountNumber, LocalDate.of(2026, 4, 19), LocalDate.of(2026, 5, 18), null));
    }

    private void assertSkipped(String sent, String subject, Account account, GmailProcessedStatus expected) throws Exception {
        org.mockito.Mockito.clearInvocations(processedRepo);
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf());
        ReconSummary summary = service.reconcile(connection, mail(sent, subject, "statement.pdf", "Your statement is attached."));
        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).as(sent + " " + subject).isEqualTo(expected);
        assertThat(row.getAccount()).isSameAs(account);
        assertThat(row.getExtractedLast4()).isEqualTo(com.financeos.domain.account.AccountService.ownLast4(account)).isNotNull();
        assertThat(summary.failed()).isZero();
    }

    private Account account(String name, String bank, AccountType type, LocalDate ingestFrom) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setBank(bank);
        a.setType(type);
        a.setIngestFromDate(ingestFrom);
        return a;
    }

    /** The account's own primary card, as AccountService.ownLast4 reads it. */
    private static void primaryCard(Account a, String last4) {
        com.financeos.domain.account.card.Cardholder holder = new com.financeos.domain.account.card.Cardholder();
        holder.setAccount(a);
        Card c = new Card();
        c.setLast4(last4);
        holder.getCards().add(c);
        a.getCardholders().add(holder);
    }

    private static void bankLast4(Account a, String last4) {
        a.setBankDetails(new AccountBankDetails(a, BigDecimal.ZERO, last4));
    }

    private Card card(Account a, String last4, LocalDate closedOn) {
        Card c = new Card();
        c.setAccount(a);
        c.setLast4(last4);
        if (closedOn != null) {
            c.close(closedOn);
        }
        when(cardRepository.findByAccountId(a.getId())).thenReturn(new ArrayList<>(List.of(c)));
        return c;
    }

    /** Pairs of start,end dates as ISO strings. */
    private static List<StatementCoverage.Period> periods(String... bounds) {
        List<StatementCoverage.Period> out = new ArrayList<>();
        for (int i = 0; i < bounds.length; i += 2) {
            out.add(new StatementCoverage.Period(UUID.randomUUID(), LocalDate.parse(bounds[i]), LocalDate.parse(bounds[i + 1])));
        }
        return out;
    }

    private GmailProcessedMessage savedRow() {
        ArgumentCaptor<GmailProcessedMessage> captor = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processedRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static GmailMessage mail(String sentDate, String subject, String filename, String body) {
        return mail(sentDate, subject, filename, body, HSBC);
    }

    private static GmailMessage mail(String sentDate, String subject, String filename, String body, String from) {
        return new GmailMessage(MESSAGE_ID, LocalDate.parse(sentDate).atTime(10, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),
                from, subject, "", body, null, List.of(new GmailAttachment("att-1", filename, "application/pdf", null)));
    }

    private static byte[] plainPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] protectedPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
