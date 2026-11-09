package com.financeos.gmail.reconcile;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.ingestion.StatementPasswordVault;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.unlock.PasswordRender;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A locked Gmail statement is judged by the account its mail names (sender bank + the mail's own words) before any
 * password is tried, and opens with the bank's rule password when none is saved.
 */
class StatementReconciliationLockedGuessTest {

    private static final String MESSAGE_ID = "msg-locked";
    private static final Instant SENT = Instant.parse("2026-06-05T04:00:00Z");

    private final GmailEngine gmailEngine = mock(GmailEngine.class);
    private final StatementParser statementParser = mock(StatementParser.class);
    private final GmailProcessedMessageRepository processedMessageRepository = mock(GmailProcessedMessageRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountResolver accountResolver = mock(AccountResolver.class);
    private final StatementPersistenceService statementPersistenceService = mock(StatementPersistenceService.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final StatementCoverage coverage = mock(StatementCoverage.class);
    private final UnlockProfileLoader profileLoader = mock(UnlockProfileLoader.class);
    private final StatementPasswordVault vault = mock(StatementPasswordVault.class);
    private final AccountIdentifierRepository identifierRepository = mock(AccountIdentifierRepository.class);

    private StatementReconciliationService service;
    private GmailConnection connection;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        service = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processedMessageRepository, accountRepository, accountResolver,
                new ReviewStatusManager(), statementPersistenceService, cardRepository,
                new ParkedMessageSettler(processedMessageRepository, accountResolver, new GmailIngestProperties()));
        service.setStatementCoverage(coverage);
        service.setUnlockProfileLoader(profileLoader);
        service.setPasswordVault(vault);
        service.setIdentifierRepository(identifierRepository);

        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        when(accountRepository.findByUserIdAndHasStatementPassword(user.getId())).thenReturn(List.of());
        when(processedMessageRepository.findByConnectionIdAndGmailMessageId(connection.getId(), MESSAGE_ID)).thenReturn(Optional.empty());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of(null, null));
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

    private void identifier(Account a, String value) {
        AccountIdentifier id = new AccountIdentifier();
        id.setValue(value);
        when(identifierRepository.findByAccountId(a.getId())).thenReturn(List.of(id));
    }

    private void card(Account a, String... last4s) {
        List<Card> cards = new java.util.ArrayList<>();
        for (String l : last4s) {
            Card c = new Card();
            c.setLast4(l);
            cards.add(c);
        }
        when(cardRepository.findByAccountId(a.getId())).thenReturn(cards);
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

    @Test
    void lockedMailBeforeTheNamedAccountsWindow_isSkippedWithoutOpeningIt() throws Exception {
        Account equitas = account("Equitas", "equitas", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        identifier(equitas, "XXX1277");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(equitas));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        ReconSummary summary = service.reconcile(connection, mail("customer-support@equitasbank.com", "Your Bank Account Statement", "XXX1277.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(equitas);
        assertThat(row.getError()).isNull();
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
        assertThat(summary.failed()).isZero();
        verify(accountRepository, never()).findByUserIdAndHasStatementPassword(any());
        verifyNoInteractions(statementParser, profileLoader);
    }

    @Test
    void lockedMailForAnAccountWithNoIngestDate_isSkippedToo() throws Exception {
        Account equitas = account("Equitas", "equitas", AccountType.bank_account, null);
        identifier(equitas, "XXX1277");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(equitas));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("customer-support@equitasbank.com", "Your Bank Account Statement", "XXX1277.pdf"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
    }

    @Test
    void lockedMailWhoseMonthIsAlreadyCovered_isSkippedAsHeldNotFailed() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(coverage.statements(hdfc.getId())).thenReturn(List.of(
                new StatementCoverage.Period(UUID.randomUUID(), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 31))));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438 statement attached"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(row.getAccount()).isSameAs(hdfc);
        verifyNoInteractions(statementParser);
    }

    @Test
    void lockedMailWhoseMonthIsOnlyPartlyCovered_goesOn() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(coverage.statements(hdfc.getId())).thenReturn(List.of(
                new StatementCoverage.Period(UUID.randomUUID(), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 20))));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438 statement attached"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getError()).isEqualTo("Encrypted statement could not be decrypted. Save the statement password on HDFC Savings.");
    }

    @Test
    void lockedMailInWindowWithNoPassword_failsNamingTheAccount() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(coverage.statements(hdfc.getId())).thenReturn(List.of());
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        ReconSummary summary = service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438 statement attached"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getAccount()).isSameAs(hdfc);
        assertThat(row.getError()).isEqualTo("Encrypted statement could not be decrypted. Save the statement password on HDFC Savings.");
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    void twoCardsAtTheBankWithNoProductHint_behavesAsBefore() throws Exception {
        Account premier = account("Premier", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        Account live = account("Live +", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(premier, live));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement", "statement.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getAccount()).isNull();
        assertThat(row.getError()).isEqualTo("Encrypted statement could not be decrypted with any stored password.");
    }

    @Test
    void productWordInTheSubjectPicksTheNamedCard() throws Exception {
        Account premier = account("Premier", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        Account live = account("Live +", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        card(live, "9724");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(premier, live));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", "statement.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(live);
        assertThat(row.getExtractedLast4()).isEqualTo("9724");
    }

    @Test
    void cardEndingThatBelongsToNoCandidate_isNotGuessed() throws Exception {
        Account bob = account("Bob Eterna", "bank_of_baroda", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        Card card = new Card();
        card.setLast4("1111");
        when(cardRepository.findByAccountId(bob.getId())).thenReturn(List.of(card));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(bob));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("estatement@bobcard.co.in", "E-statement for your BOBCARD VISA ETERNA credit card ending in 5554 - May 2026", "statement.pdf"));

        assertThat(savedRow().getAccount()).isNull();
    }

    @Test
    void ruleBuiltPasswordOpensTheFile_andIsSavedOnTheResolvedAccount() throws Exception {
        String password = PasswordRender.renderOne("NAME4U+DDMM", "Adarsh Jain", "1990-05-17", null);
        Account federal = account("Federal", "federal", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(federal, "4321");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(federal));
        when(coverage.statements(federal.getId())).thenReturn(List.of());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of("Adarsh Jain", "1990-05-17"));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf(password));
        when(statementParser.parse(any(), eq(password))).thenReturn(StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 5, 3), new BigDecimal("10.00"), "DEBIT", "x", null, null)),
                "XXXX4321", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null));
        when(accountResolver.resolve(eq("XXXX4321"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(federal, null)));

        service.reconcile(connection, mail("alerts@federalbank.co.in", "Federal Bank Account Statement", "statement.pdf", "Account XXXXXX4321"));

        verify(vault).saveIfMissing(user.getId(), federal.getId(), password);
        verify(statementParser).parse(any(), eq(password));
    }

    @Test
    void partnerBrandMail_unlocksByThePartnerBanksRule_andSavesThePassword() throws Exception {
        String password = PasswordRender.renderOne("NAME4U+DDMM", "Adarsh Jain", "1990-05-17", null);
        Account federal = account("Fi Money", "federal", AccountType.bank_account, LocalDate.of(2021, 12, 1));
        bankLast4(federal, "3974");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(federal));
        when(coverage.statements(federal.getId())).thenReturn(List.of());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of("Adarsh Jain", "1990-05-17"));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf(password));
        when(statementParser.parse(any(), eq(password))).thenReturn(StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2023, 5, 3), new BigDecimal("10.00"), "DEBIT", "x", null, null)),
                "XXXX3974", LocalDate.of(2023, 5, 1), LocalDate.of(2023, 5, 31), null));
        when(accountResolver.resolve(eq("XXXX3974"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(federal, null)));

        service.reconcile(connection, mail("no-reply@fi.money", "Your Fi account statement", "statement.pdf", "Account XXXXXX3974"));

        verify(vault).saveIfMissing(user.getId(), federal.getId(), password);
    }

    @Test
    void noBirthDate_rulesAreSkipped() throws Exception {
        String password = PasswordRender.renderOne("NAME4U+DDMM", "Adarsh Jain", "1990-05-17", null);
        Account federal = account("Federal", "federal", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(federal, "4321");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(federal));
        when(coverage.statements(federal.getId())).thenReturn(List.of());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of("Adarsh Jain", null));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf(password));

        service.reconcile(connection, mail("alerts@federalbank.co.in", "Federal Bank Account Statement", "statement.pdf", "Account XXXXXX4321"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        verifyNoInteractions(statementParser, vault);
    }

    @Test
    void aRulePasswordThatOpensNothing_leavesTheFileLocked() throws Exception {
        Account federal = account("Federal", "federal", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(federal, "4321");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(federal));
        when(coverage.statements(federal.getId())).thenReturn(List.of());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of("Adarsh Jain", "1990-05-17"));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("unrelated"));

        service.reconcile(connection, mail("alerts@federalbank.co.in", "Federal Bank Account Statement", "statement.pdf", "Account XXXXXX4321"));

        assertThat(savedRow().getError()).contains("Save the statement password on Federal");
        verifyNoInteractions(vault);
    }

    @Test
    void singleCardAtTheBankWithNothingToCorroborate_isNotGuessed() throws Exception {
        Account premier = account("Premier", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        card(premier, "7106");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(premier));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement (HSBC TRAVELONE)", "statement.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getAccount()).isNull();
    }

    @Test
    void digitsThatBelongToNoKnownNumberOfTheAccount_areNotGuessed() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX1111"));

        assertThat(savedRow().getAccount()).isNull();
    }

    @Test
    void accountClosedWithinFortyFiveDaysOfTheMail_isStillOpened() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        hdfc.setClosedOn(LocalDate.of(2026, 5, 26));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(coverage.statements(hdfc.getId())).thenReturn(List.of());
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getError()).contains("Save the statement password on HDFC Savings");
    }

    @Test
    void accountClosedLongBeforeTheMail_isSkipped() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        hdfc.setClosedOn(LocalDate.of(2026, 3, 1));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getExtractedLast4()).isEqualTo("0438");
    }

    @Test
    void guessWithNoDigitsToKeepOnTheRow_isSkippedBeforeItsWindow() throws Exception {
        Account live = account("Live +", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        card(live, "9724", "5754");
        primaryCard(live, "9724");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(live));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", "statement.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getAccount()).isSameAs(live);
        // the account's own last4 (what AccountIngestChangedEvent carries), so an earlier ingest date reopens the row
        assertThat(row.getExtractedLast4()).isEqualTo(com.financeos.domain.account.AccountService.ownLast4(live)).isEqualTo("9724");
    }

    @Test
    void guessWithNoLast4AtAll_isNotSkipped() throws Exception {
        Account live = account("Live +", "hsbc", AccountType.credit_card, LocalDate.of(2026, 10, 7));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(live));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", "statement.pdf"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
    }

    @Test
    void guessWithNoDigitsInsideItsWindowAndNotCovered_stillAsksForThePassword() throws Exception {
        Account live = account("Live +", "hsbc", AccountType.credit_card, LocalDate.of(2026, 5, 1));
        card(live, "9724", "5754");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(live));
        when(coverage.statements(live.getId())).thenReturn(List.of());
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("hsbc@mail.hsbc.co.in", "Your HSBC Credit Card statement (HSBC LIVE+ CREDIT CARD)", "statement.pdf"));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getError()).contains("Save the statement password on Live +");
    }

    @Test
    void namedMonthCoveredButTheMonthBeforeTheMailIsNot_isNotSkipped() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        // the mail names March (covered) but was sent in June: May is not covered
        when(coverage.statements(hdfc.getId())).thenReturn(List.of(
                new StatementCoverage.Period(UUID.randomUUID(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for Mar-2026", "statement.pdf", "Account XXXXXX0438"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
    }

    @Test
    void monthBeforeTheMailCoveredButTheNamedMonthIsNot_isNotSkipped() throws Exception {
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(coverage.statements(hdfc.getId())).thenReturn(List.of(
                new StatementCoverage.Period(UUID.randomUUID(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for Mar-2026", "statement.pdf", "Account XXXXXX0438"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
    }

    @Test
    void withoutAStatementCoverageBean_theCoveredCheckIsSkipped() throws Exception {
        StatementReconciliationService bare = new StatementReconciliationService(gmailEngine, statementParser, mock(TransactionRepository.class),
                processedMessageRepository, accountRepository, accountResolver,
                new ReviewStatusManager(), statementPersistenceService, cardRepository,
                new ParkedMessageSettler(processedMessageRepository, accountResolver, new GmailIngestProperties()));
        Account hdfc = account("HDFC Savings", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(hdfc, "0438");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(hdfc));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        bare.reconcile(connection, mail("alerts@hdfcbank.net", "HDFC Bank Combined Email Statement for May-2026", "statement.pdf", "Account XXXXXX0438"));

        assertThat(savedRow().getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
    }

    @Test
    void ruleFoundPassword_isNotSavedOnAnAccountOfAnotherBank() throws Exception {
        String password = PasswordRender.renderOne("NAME4U+DDMM", "Adarsh Jain", "1990-05-17", null);
        Account federal = account("Federal", "federal", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        bankLast4(federal, "4321");
        Account other = account("Other", "hdfc", AccountType.bank_account, LocalDate.of(2026, 1, 1));
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(federal));
        when(coverage.statements(federal.getId())).thenReturn(List.of());
        when(profileLoader.load(user.getId())).thenReturn(UnlockProfile.of("Adarsh Jain", "1990-05-17"));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf(password));
        when(statementParser.parse(any(), eq(password))).thenReturn(StatementExtractionResult.success(
                List.of(new ParsedStatementLine(LocalDate.of(2026, 5, 3), new BigDecimal("10.00"), "DEBIT", "x", null, null)),
                "XXXX9999", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), null));
        when(accountResolver.resolve(eq("XXXX9999"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(other, null)));

        service.reconcile(connection, mail("alerts@federalbank.co.in", "Federal Bank Account Statement", "statement.pdf", "Account XXXXXX4321"));

        verifyNoInteractions(vault);
    }

    @Test
    void unmaskedCustomerIdTail_corroboratesThroughAnIdentifier() throws Exception {
        Account equitas = account("Equitas", "equitas", AccountType.bank_account, LocalDate.of(2026, 10, 7));
        identifier(equitas, "XXX1277");
        when(accountRepository.findByUserId(user.getId())).thenReturn(List.of(equitas));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn(protectedPdf("secret"));

        service.reconcile(connection, mail("customer-support@equitasbank.com", "Your Bank Account Statement", "8111277.pdf",
                "Your e-statement for JAN-25 for customer ID 8111277 is attached."));

        GmailProcessedMessage row = savedRow();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(row.getExtractedLast4()).isEqualTo("1277");
    }

    @Test
    void monthInSubject_readsTheCommonForms() {
        assertThat(StatementAccountGuess.monthInSubject("Combined Email Statement for May-2026")).contains(java.time.YearMonth.of(2026, 5));
        assertThat(StatementAccountGuess.monthInSubject("e-statement SEP-26")).contains(java.time.YearMonth.of(2026, 9));
        assertThat(StatementAccountGuess.monthInSubject("credit card ending in 5554 - May 2026")).contains(java.time.YearMonth.of(2026, 5));
        assertThat(StatementAccountGuess.monthInSubject("Your Bank Account Statement")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private GmailProcessedMessage savedRow() {
        ArgumentCaptor<GmailProcessedMessage> captor = ArgumentCaptor.forClass(GmailProcessedMessage.class);
        verify(processedMessageRepository).save(captor.capture());
        return captor.getValue();
    }

    private static GmailMessage mail(String from, String subject, String filename) {
        return mail(from, subject, filename, "Your statement is attached.");
    }

    private static GmailMessage mail(String from, String subject, String filename, String body) {
        return new GmailMessage(MESSAGE_ID, SENT, from, subject, "", body, null,
                List.of(new GmailAttachment("att-1", filename, "application/pdf", null)));
    }

    private static byte[] protectedPdf(String userPassword) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        }
    }
}
