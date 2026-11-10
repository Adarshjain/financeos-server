package com.financeos.domain.ingestion;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.ingestion.StatementPasswordFinder.Found;
import com.financeos.domain.ingestion.StatementPasswordFinder.Source;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
import com.financeos.statement.parser.ProtectedPdfs;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Passwords here are made up; profile: Riya Sharma, born 14 Aug 1994 (ICICI's table rule NAME4L+DDMM gives riya1408). */
class StatementPasswordFinderTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID ownId = UUID.randomUUID();
    private AccountRepository accounts;
    private StatementPasswordVault vault;
    private UnlockProfileLoader profileLoader;
    private PlatformTransactionManager tm;
    private Account own;
    private final List<Account> withPassword = new ArrayList<>();
    private final List<String> vaultPasswords = new ArrayList<>();
    private Clock clock = Clock.fixed(java.time.Instant.parse("2026-11-10T10:00:00Z"), java.time.ZoneOffset.UTC);
    private final List<Boolean> txOpenDuringTrial = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        vault = mock(StatementPasswordVault.class);
        profileLoader = mock(UnlockProfileLoader.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus(true));
        own = bank(ownId, "icici", null);
        when(accounts.findByIdAndUser_Id(ownId, userId)).thenAnswer(i -> java.util.Optional.of(own));
        when(accounts.findByUserIdAndHasStatementPassword(userId)).thenAnswer(i -> new ArrayList<>(withPassword));
        when(vault.savedPasswords(userId)).thenAnswer(i -> new ArrayList<>(vaultPasswords));
        when(profileLoader.load(userId)).thenReturn(new UnlockProfile("Riya Sharma", "1994-08-14", java.util.Map.of()));
    }

    private StatementPasswordFinder finder() {
        return new StatementPasswordFinder(accounts, vault, profileLoader, StatementUnlocker.bundled(), tm, clock, 10);
    }

    private static Account bank(UUID id, String bank, String password) {
        Account a = new Account();
        a.setId(id);
        a.setBank(bank);
        a.setType(AccountType.bank_account);
        AccountBankDetails d = new AccountBankDetails();
        d.setStatementPassword(password);
        a.setBankDetails(d);
        return a;
    }

    private static Account card(UUID id, String bank, String password) {
        Account a = new Account();
        a.setId(id);
        a.setBank(bank);
        a.setType(AccountType.credit_card);
        AccountCreditCardDetails d = new AccountCreditCardDetails();
        d.setStatementPassword(password);
        a.setCreditCardDetails(d);
        return a;
    }

    private Account other(String password) {
        Account a = bank(UUID.randomUUID(), "hdfc", password);
        withPassword.add(a);
        return a;
    }

    @Test
    void theStatementsOwnAccountPasswordOpensItFirst() throws Exception {
        ((AccountBankDetails) own.getBankDetails()).setStatementPassword("own-secret");
        withPassword.add(own);
        other("own-secret");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("own-secret"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OWN_ACCOUNT);
        assertThat(f.password()).isEqualTo("own-secret");
        assertThat(f.opened()).isTrue();
        assertThat(f.capHit()).isFalse();
        verifyNoInteractions(profileLoader);
    }

    @Test
    void aCreditCardAccountsOwnPasswordIsRead() throws Exception {
        own = card(ownId, "hdfc", "card-secret");

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("card-secret"), true, java.util.Set.of()).source()).isEqualTo(Source.OWN_ACCOUNT);
    }

    @Test
    void theOwnPasswordsOtherCaseOpensItAndIsReportedAsTheOwnAccount() throws Exception {
        ((AccountBankDetails) own.getBankDetails()).setStatementPassword("AbCd1234");

        Found lower = finder().find(userId, ownId, ProtectedPdfs.pdf("abcd1234"), true, java.util.Set.of());
        Found upper = finder().find(userId, ownId, ProtectedPdfs.pdf("ABCD1234"), true, java.util.Set.of());

        assertThat(lower.source()).isEqualTo(Source.OWN_ACCOUNT);
        assertThat(lower.password()).isEqualTo("abcd1234");
        assertThat(upper.source()).isEqualTo(Source.OWN_ACCOUNT);
        assertThat(upper.password()).isEqualTo("ABCD1234");
    }

    @Test
    void anotherAccountsPasswordOpensItWhenTheOwnOneDoesNot() throws Exception {
        ((AccountBankDetails) own.getBankDetails()).setStatementPassword("wrong-own");
        withPassword.add(own);
        other("not-it");
        other("sibling-secret");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("sibling-secret"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.password()).isEqualTo("sibling-secret");
    }

    @Test
    void anotherAccountsCaseVariantOpensIt() throws Exception {
        other("AbCd9999");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("abcd9999"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.password()).isEqualTo("abcd9999");
    }

    @Test
    void theOwnAccountsVariantIsTriedBeforeOtherAccountsPasswords() throws Exception {
        ((AccountBankDetails) own.getBankDetails()).setStatementPassword("Zz11zz11");
        other("zz11zz11"); // an exact password of another account that also opens a file locked with the lowercase form

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("zz11zz11"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OWN_ACCOUNT);
    }

    @Test
    void aVaultPasswordNotOnAnyAccountOpensIt() throws Exception {
        other("nope");
        vaultPasswords.add("nope");
        vaultPasswords.add("vault-only");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("vault-only"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.VAULT);
        assertThat(f.password()).isEqualTo("vault-only");
    }

    @Test
    void aPasswordBuiltFromTheBanksRulesOpensItWhenNothingSavedDoes() throws Exception {
        other("unrelated");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.BANK_RULES);
        assertThat(f.password()).isEqualTo("riya1408");
    }

    @Test
    void theBankRulesUseTheStatementsAccountBankNotAnotherAccountsBank() throws Exception {
        own = bank(ownId, "hdfc", null); // hdfc builds a different password than icici's riya1408

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of()).opened()).isFalse();
    }

    @Test
    void aSavedPasswordWinsOverARuleBuiltOne() throws Exception {
        other("riya1408"); // saved on a sibling and also what the rule would build

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of()).source()).isEqualTo(Source.OTHER_ACCOUNT);
    }

    @Test
    void rulesAreSkippedWithoutBirthDateNameOrBank() throws Exception {
        byte[] file = ProtectedPdfs.pdf("riya1408");
        when(profileLoader.load(userId)).thenReturn(new UnlockProfile("Riya Sharma", null, java.util.Map.of()));
        assertThat(finder().find(userId, ownId, file, true, java.util.Set.of()).opened()).isFalse();
        when(profileLoader.load(userId)).thenReturn(new UnlockProfile(" ", "1994-08-14", java.util.Map.of()));
        assertThat(finder().find(userId, ownId, file, true, java.util.Set.of()).opened()).isFalse();
        when(profileLoader.load(userId)).thenReturn(new UnlockProfile("Riya Sharma", "1994-08-14", java.util.Map.of()));
        own.setBank(null);
        assertThat(finder().find(userId, ownId, file, true, java.util.Set.of()).opened()).isFalse();
    }

    @Test
    void aFailingProfileLoadSkipsTheRulesInsteadOfFailing() throws Exception {
        when(profileLoader.load(userId)).thenThrow(new IllegalStateException("db down"));

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
    }

    @Test
    void theCardsLastFourFeedsTheCard4Rule() throws Exception {
        // hdfc credit-card rules in the bundled table may use CARD4; the finder offers the card's last 4 and the
        // account's last 4 (here the bank account's) as the candidate numbers, so a password built from them is found
        own = bank(ownId, "hdfc", null);
        ((AccountBankDetails) own.getBankDetails()).setLast4("4381");
        StatementUnlocker spy = mock(StatementUnlocker.class);
        when(spy.openingPassword(any(), any(), any())).thenReturn("built-pw");

        Found f = new StatementPasswordFinder(accounts, vault, profileLoader, spy, tm, clock, 10)
                .find(userId, ownId, ProtectedPdfs.pdf("whatever"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.BANK_RULES);
        org.mockito.ArgumentCaptor<StatementUnlocker.Target> t = org.mockito.ArgumentCaptor.forClass(StatementUnlocker.Target.class);
        verify(spy).openingPassword(any(), t.capture(), any());
        assertThat(t.getValue().bank()).isEqualTo("hdfc");
        assertThat(t.getValue().cardLast4()).containsExactly("4381");
        assertThat(t.getValue().hintRules()).isEmpty();
        assertThat(t.getValue().kind()).isEqualTo(com.financeos.gmail.accountdetect.Vocab.Kind.ACCOUNT);
    }

    @Test
    void aCreditCardAccountAsksTheCreditCardRules() throws Exception {
        own = card(ownId, "hdfc", null);
        StatementUnlocker spy = mock(StatementUnlocker.class);

        new StatementPasswordFinder(accounts, vault, profileLoader, spy, tm, clock, 10).find(userId, ownId, ProtectedPdfs.pdf("x"), true, java.util.Set.of());

        org.mockito.ArgumentCaptor<StatementUnlocker.Target> t = org.mockito.ArgumentCaptor.forClass(StatementUnlocker.Target.class);
        verify(spy).openingPassword(any(), t.capture(), any());
        assertThat(t.getValue().kind()).isEqualTo(com.financeos.gmail.accountdetect.Vocab.Kind.CREDIT_CARD);
    }

    @Test
    void nothingOpeningItIsNotFoundAndNotCapped() throws Exception {
        other("a-pass");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("zzz"), true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        assertThat(f.source()).isNull();
        assertThat(f.password()).isNull();
        assertThat(f.capHit()).isFalse();
    }

    @Test
    void aStatementWhoseAccountIsNotTheUsersFindsNothingFromRulesButStillTriesSavedPasswords() throws Exception {
        UUID stranger = UUID.randomUUID();
        other("saved-one");

        Found f = finder().find(userId, stranger, ProtectedPdfs.pdf("saved-one"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(finder().find(userId, stranger, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of()).opened()).isFalse();
    }

    @Test
    void atMostTwentySavedCandidatesAreTriedThenTheCapIsReported() throws Exception {
        for (int i = 0; i < 30; i++) other("pw" + i + "xx"); // 30 accounts x 2 forms = 60 candidates
        byte[] file = ProtectedPdfs.pdf("zzzz");

        Found f = finder().find(userId, ownId, file, true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        assertThat(f.capHit()).isTrue();
    }

    @Test
    void aPasswordBeyondTheCapIsNotTried() throws Exception {
        for (int i = 0; i < 20; i++) other("p" + i + "aa");
        other("late-one"); // the 21st exact candidate

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("late-one"), true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        assertThat(f.capHit()).isTrue();
    }

    @Test
    void theTwentiethCandidateIsStillTried() throws Exception {
        for (int i = 0; i < 19; i++) other("p" + i + "aa");
        other("last-ok");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("last-ok"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.capHit()).isFalse();
    }

    @Test
    void ruleBuiltPasswordsAreStillTriedWhenTheSavedOnesHitTheCap() throws Exception {
        for (int i = 0; i < 25; i++) other("q" + i + "bb");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.BANK_RULES);
    }

    @Test
    void anEncryptedExcelFileIsOpenedByTheSavedPasswordsAndNeverByRules() throws Exception {
        byte[] xlsx = encryptedXlsx("xl-secret");
        other("xl-secret");

        Found f = finder().find(userId, ownId, xlsx, false, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.password()).isEqualTo("xl-secret");
    }

    @Test
    void anExcelFileWithNoSavedPasswordDoesNotReachTheRules() throws Exception {
        StatementUnlocker spy = mock(StatementUnlocker.class);

        Found f = new StatementPasswordFinder(accounts, vault, profileLoader, spy, tm, clock, 10).find(userId, ownId, encryptedXlsx("riya1408"), false, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        verify(spy, never()).openingPassword(any(), any(), any());
        verifyNoInteractions(profileLoader);
    }

    @Test
    void nothingIsSavedAnywhere() throws Exception {
        other("sib");
        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("sib"), true, java.util.Set.of());
        finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of());

        assertThat(f.opened()).isTrue();
        verify(vault, never()).saveIfMissing(any(), any(), any());
        verify(vault, never()).save(any(), any(), any());
        verify(accounts, never()).save(any());
    }

    @Test
    void theResultNeverPrintsThePassword() throws Exception {
        other("print-me-not");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("print-me-not"), true, java.util.Set.of());

        assertThat(f.toString()).doesNotContain("print-me-not").contains("OTHER_ACCOUNT");
    }

    @Test
    void accountDataIsReadInTransactionsThatAreClosedBeforeAnyPasswordIsTried() throws Exception {
        other("sib");
        int[] active = {0};
        when(tm.getTransaction(any())).thenAnswer(i -> {
            active[0]++;
            return new SimpleTransactionStatus(true);
        });
        org.mockito.Mockito.doAnswer(i -> {
            active[0]--;
            return null;
        }).when(tm).commit(any());
        // the vault read runs after the trials of steps 1-2 and must see no open transaction of the finder's own
        when(vault.savedPasswords(userId)).thenAnswer(i -> {
            txOpenDuringTrial.add(active[0] > 0);
            return List.of();
        });

        finder().find(userId, ownId, ProtectedPdfs.pdf("zzz"), true, java.util.Set.of());

        assertThat(txOpenDuringTrial).containsExactly(false);
        verify(tm).commit(any());
    }

    private static byte[] withPreamble(byte[] pdf) {
        byte[] pre = "HSBC statement\r\n\r\n".getBytes();
        byte[] out = new byte[pre.length + pdf.length];
        System.arraycopy(pre, 0, out, 0, pre.length);
        System.arraycopy(pdf, 0, out, pre.length, pdf.length);
        return out;
    }

    @Test
    void aLockedPdfWithAPreambleIsOpenedLikeTheParserReadsIt() throws Exception {
        other("pre-secret");

        Found f = finder().find(userId, ownId, withPreamble(ProtectedPdfs.pdf("pre-secret")), true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.password()).isEqualTo("pre-secret");
    }

    @Test
    void aPreambleStillAllowsTheBankRules() throws Exception {
        assertThat(finder().find(userId, ownId, withPreamble(ProtectedPdfs.pdf("riya1408")), true, java.util.Set.of()).source())
                .isEqualTo(Source.BANK_RULES);
    }

    @Test
    void aMalformedFileThatThrowsARuntimeErrorOnATrialIsJustNotOpenedAndLaterSourcesRun() throws Exception {
        other("whatever");
        // not a PDF at all: PDFBox / POI throw on every trial; no source opens it and nothing escapes
        byte[] junk = "%PDF-1.4 truncated garbage".getBytes();

        Found f = finder().find(userId, ownId, junk, true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        verify(profileLoader).load(userId); // the rules step still ran after the failed trials
    }

    @Test
    void aRuntimeErrorOnOneTrialDoesNotStopTheNextCandidate() throws Exception {
        // the first candidate's trial throws a runtime error; the search goes on and the rules still find the password
        StatementUnlocker spy = mock(StatementUnlocker.class);
        when(spy.openingPassword(any(), any(), any())).thenReturn("from-rules");
        other("a");

        Found f = new StatementPasswordFinder(accounts, vault, profileLoader, spy, tm, clock, 10)
                .find(userId, ownId, new byte[0], true, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.BANK_RULES);
    }

    /** Each reading of the clock moves it forward {@code stepSeconds}. */
    private static Clock stepping(long stepSeconds) {
        return new Clock() {
            private java.time.Instant now = java.time.Instant.parse("2026-11-10T10:00:00Z");

            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() {
                java.time.Instant r = now;
                now = now.plusSeconds(stepSeconds);
                return r;
            }
        };
    }

    @Test
    void whenTheDeadlinePassesTheSearchStopsAsNotFoundWithTheCapFlag() throws Exception {
        other("a1b2c3");
        other("late-secret");
        // 6 s per reading, 10 s budget: start t0, first trial check t+6 passes, the second t+12 has expired
        clock = stepping(6);

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("late-secret"), true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        assertThat(f.capHit()).isTrue();
    }

    @Test
    void anExpiredDeadlineSkipsTheRulesToo() throws Exception {
        clock = stepping(100); // already past the budget at the first check

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("riya1408"), true, java.util.Set.of());

        assertThat(f.opened()).isFalse();
        assertThat(f.capHit()).isTrue();
        verifyNoInteractions(profileLoader);
    }

    @Test
    void aSlowButInTimeSearchStillFinds() throws Exception {
        other("quick");
        clock = stepping(1);

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("quick"), true, java.util.Set.of()).opened()).isTrue();
    }

    @Test
    void passwordsTheCallerAlreadyTriedAreNotTriedAgain() throws Exception {
        other("tried-before");

        Found skipped = finder().find(userId, ownId, ProtectedPdfs.pdf("tried-before"), true, java.util.Set.of("tried-before"));
        Found normal = finder().find(userId, ownId, ProtectedPdfs.pdf("tried-before"), true, java.util.Set.of());

        assertThat(skipped.opened()).isFalse();
        assertThat(normal.opened()).isTrue();
    }

    @Test
    void anAlreadyTriedPasswordsCaseVariantIsStillTried() throws Exception {
        other("MiXeD11");

        Found f = finder().find(userId, ownId, ProtectedPdfs.pdf("mixed11"), true, java.util.Set.of("MiXeD11"));

        assertThat(f.password()).isEqualTo("mixed11");
    }

    @Test
    void aCardAccountWithoutACardPasswordFallsBackToItsBankDetailsPasswordLikeTheVault() throws Exception {
        Account both = card(UUID.randomUUID(), "hdfc", null);
        AccountBankDetails bd = new AccountBankDetails();
        bd.setStatementPassword("fallback-pw");
        both.setBankDetails(bd);
        withPassword.add(both);

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("fallback-pw"), true, java.util.Set.of()).source())
                .isEqualTo(Source.OTHER_ACCOUNT);
    }

    @Test
    void anotherUsersAccountPasswordsAndVaultEntriesAreNeverCandidates() throws Exception {
        UUID otherUser = UUID.randomUUID();
        Account theirs = bank(UUID.randomUUID(), "hdfc", "their-secret");
        when(accounts.findByUserIdAndHasStatementPassword(otherUser)).thenReturn(List.of(theirs));
        when(vault.savedPasswords(otherUser)).thenReturn(List.of("their-vault-secret"));
        other("mine");

        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("their-secret"), true, java.util.Set.of()).opened()).isFalse();
        assertThat(finder().find(userId, ownId, ProtectedPdfs.pdf("their-vault-secret"), true, java.util.Set.of()).opened()).isFalse();
        verify(accounts, never()).findByUserIdAndHasStatementPassword(otherUser);
        verify(vault, never()).savedPasswords(otherUser);
        verify(profileLoader, never()).load(otherUser);
    }

    @Test
    void anEncryptedLegacyXlsIsNotOpenedByAWrongPasswordAndIsByTheRightOne() throws Exception {
        byte[] xls = encryptedXls("xls-secret");
        other("wrong-one");
        assertThat(finder().find(userId, ownId, xls, false, java.util.Set.of()).opened()).isFalse();

        other("xls-secret");
        Found f = finder().find(userId, ownId, xls, false, java.util.Set.of());

        assertThat(f.source()).isEqualTo(Source.OTHER_ACCOUNT);
        assertThat(f.password()).isEqualTo("xls-secret");
    }

    private static byte[] encryptedXls(String password) throws Exception {
        org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(password);
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet().createRow(0).createCell(0).setCellValue("x");
            wb.write(out);
            return out.toByteArray();
        } finally {
            org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null);
        }
    }

    private static byte[] encryptedXlsx(String password) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
            wb.createSheet().createRow(0).createCell(0).setCellValue("x");
            wb.write(plain);
            POIFSFileSystem fs = new POIFSFileSystem();
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(plain.toByteArray()));
                 java.io.OutputStream os = enc.getDataStream(fs)) {
                opc.save(os);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }
}
