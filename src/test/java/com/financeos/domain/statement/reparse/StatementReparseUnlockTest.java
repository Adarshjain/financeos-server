package com.financeos.domain.statement.reparse;

import com.financeos.core.security.UserContext;
import com.financeos.core.security.UserFilterEnabler;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.ReconciledRefresh;
import com.financeos.domain.ingestion.StatementOpener;
import com.financeos.domain.ingestion.StatementPasswordFinder;
import com.financeos.domain.ingestion.StatementPasswordFinder.Found;
import com.financeos.domain.ingestion.StatementPasswordFinder.Source;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementParseRecords;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.statement.parser.StatementParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A locked stored file: after the upload path's passwords fail, the re-read job asks the finder and parses with its password. */
class StatementReparseUnlockTest {

    private static final int V = 3;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.7 locked".getBytes();
    private static final byte[] XLSX = "PK-encrypted".getBytes();

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private StatementRepository statements;
    private StatementFileStore store;
    private AccountRepository accounts;
    private StatementOpener opener;
    private StatementPasswordFinder finder;
    private StatementPersistenceService persistence;
    private SimpleMeterRegistry meters;
    private Account account;

    private static final StatementOpener.Opened LOCKED = new StatementOpener.Opened(
            StatementExtractionResult.failure("ERROR: PDF is password-protected", StatementParseException.Kind.LOCKED), true);

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        store = mock(StatementFileStore.class);
        accounts = mock(AccountRepository.class);
        opener = mock(StatementOpener.class);
        finder = mock(StatementPasswordFinder.class);
        persistence = mock(StatementPersistenceService.class);
        meters = new SimpleMeterRegistry();
        account = new Account();
        account.setId(accountId);
        account.setBankDetails(new AccountBankDetails());
        when(accounts.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.of(account));
        when(store.load(userId, fileId)).thenReturn(Optional.of(PDF));
        StatementRepository.ReparseCandidate c = mock(StatementRepository.ReparseCandidate.class);
        when(c.getId()).thenReturn(statementId.toString());
        when(c.getUserId()).thenReturn(userId.toString());
        when(c.getAccountId()).thenReturn(accountId.toString());
        when(c.getFileId()).thenReturn(fileId.toString());
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(List.of(c));
        when(statements.markReparseStarted(any(), anyInt(), any())).thenReturn(1);
        when(statements.markParsedAtVersion(any(), anyInt(), any())).thenReturn(1);
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(LOCKED);
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new Found(null, null, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private StatementReparse job() {
        return new StatementReparse(statements, store, accounts, mock(UserRepository.class), opener, finder, persistence,
                mock(ReconciledRefresh.class), mock(StatementParseRecords.class), mock(UserFilterEnabler.class), meters,
                new StatementReparseTest.RecordingTx(), Clock.fixed(NOW, ZoneOffset.UTC), V, true, false, 5, 45);
    }

    private double unlockCount(String source) {
        var c = meters.find("financeos.statement.reparse.unlock").tag("source", source).counter();
        return c == null ? 0 : c.count();
    }

    private void stamped(String outcome, String summary) {
        verify(statements).markReparse(eq(statementId), eq(V), any(), eq(outcome), summary == null ? isNull() : eq(summary));
    }

    private static StatementDraft draft() {
        return new StatementDraft("bank_account", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), BigDecimal.ZERO,
                new BigDecimal("100"), "HSBC", "xx1234", 1, 0, BigDecimal.TEN, BigDecimal.TEN, "balance_chain",
                new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, Map.of(), null, Set.of(), 3, "{\"snap\":1}");
    }

    private static StatementOpener.Opened parsed() {
        StatementDraft d = draft();
        return new StatementOpener.Opened(new StatementExtractionResult(true,
                List.of(new ParsedStatementLine(LocalDate.of(2026, 1, 5), BigDecimal.TEN, "DEBIT", "Coffee", null, null)),
                "xx1234", d.periodStart(), d.periodEnd(), d, null, "text"), true);
    }

    @Test
    void aWorkingPasswordFromTheFinderParsesTheFileAndNothingIsSavedAnywhere() {
        ((AccountBankDetails) account.getBankDetails()).setStatementPassword("acct");
        when(finder.find(userId, accountId, PDF, true, java.util.Set.of("acct"))).thenReturn(new Found(Source.OTHER_ACCOUNT, "sibling", false));
        when(opener.open(any(), any(), any(), eq("sibling"), any(), anyBoolean())).thenReturn(parsed());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        job().run();

        // first the upload path's way (the account's password), then the found password, always read-only
        verify(opener).open(userId, accountId, PDF, null, "acct", false);
        verify(opener).open(userId, accountId, PDF, "sibling", "acct", false);
        verify(opener, never()).open(any(), any(), any(), any(), any(), eq(true));
        // it went on to the compare step (no target: refresh returns nothing), not to PARSE_FAILED
        stamped("NO_TARGET", null);
        assertThat(unlockCount("OTHER_ACCOUNT")).isEqualTo(1);
    }

    @Test
    void everySourceIsCountedUnderItsOwnTag() {
        for (Source s : Source.values()) {
            when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new Found(s, "pw", false));
            when(opener.open(any(), any(), any(), eq("pw"), any(), anyBoolean())).thenReturn(parsed());
            when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
            job().run();
        }

        for (Source s : Source.values()) {
            assertThat(unlockCount(s.name())).as(s.name()).isEqualTo(1);
        }
    }

    @Test
    void aFileNoPasswordOpensIsParseFailedLockedAndCountedAsNone() {
        job().run();

        stamped("PARSE_FAILED", "LOCKED");
        assertThat(unlockCount("NONE")).isEqualTo(1);
        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aSearchCutShortByTheCapIsLockedCap() {
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new Found(null, null, true));

        job().run();

        stamped("PARSE_FAILED", "LOCKED:cap");
        assertThat(unlockCount("CAP")).isEqualTo(1);
    }

    @Test
    void thePdfFlagIsTrueForAPdfAndFalseForAnExcelFile() {
        job().run();
        verify(finder).find(userId, accountId, PDF, true, Set.of());

        when(store.load(userId, fileId)).thenReturn(Optional.of(XLSX));
        when(statements.markReparseStarted(any(), anyInt(), any())).thenReturn(1);
        job().run();
        verify(finder).find(userId, accountId, XLSX, false, Set.of());
    }

    @Test
    void aPdfWithAPreambleIsSearchedAsAPdf() {
        byte[] withPreamble = "HSBC preamble\r\n%PDF-1.4 locked".getBytes();
        when(store.load(userId, fileId)).thenReturn(Optional.of(withPreamble));

        job().run();

        verify(finder).find(userId, accountId, withPreamble, true, Set.of());
    }

    @Test
    void theAccountsOwnPasswordIsPassedAsAlreadyTriedAndAnAccountWithoutOneNothing() {
        ((AccountBankDetails) account.getBankDetails()).setStatementPassword("acct");
        job().run();
        verify(finder).find(userId, accountId, PDF, true, Set.of("acct"));
    }

    @Test
    void anExcelFileOpenedByAFoundPasswordIsParsedWithIt() {
        when(store.load(userId, fileId)).thenReturn(Optional.of(XLSX));
        when(finder.find(userId, accountId, XLSX, false, java.util.Set.of())).thenReturn(new Found(Source.OTHER_ACCOUNT, "xl", false));
        when(opener.open(any(), any(), any(), eq("xl"), any(), anyBoolean())).thenReturn(parsed());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        job().run();

        verify(opener).open(userId, accountId, XLSX, "xl", null, false);
        stamped("NO_TARGET", null);
    }

    @Test
    void aFoundPasswordThatStillFailsToParseRecordsThatFailureKind() {
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new Found(Source.BANK_RULES, "built", false));
        when(opener.open(any(), any(), any(), eq("built"), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(
                StatementExtractionResult.failure("ERROR: unreadable", StatementParseException.Kind.UNREADABLE), true));

        job().run();

        stamped("PARSE_FAILED", "UNREADABLE");
    }

    @Test
    void aPasswordMessageWithoutAKindIsAlsoSearched() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(
                StatementExtractionResult.failure("ERROR: PDF is password-protected and wrong", StatementParseException.Kind.OTHER), true));

        job().run();

        verify(finder).find(any(), any(), any(), anyBoolean(), any());
        stamped("PARSE_FAILED", "LOCKED");
    }

    @Test
    void otherFailuresNeverAskTheFinder() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(
                StatementExtractionResult.failure("ERROR: corrupt", StatementParseException.Kind.UNREADABLE), false));

        job().run();

        verifyNoInteractions(finder);
        stamped("PARSE_FAILED", "UNREADABLE");
        assertThat(unlockCount("NONE")).isZero();
    }

    @Test
    void aFileTheUploadPathOpensNeverAsksTheFinder() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(parsed());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        job().run();

        verifyNoInteractions(finder);
    }

    @Test
    void aFinderThatThrowsEndsTheStatementAsErrorNotAHungRun() {
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenThrow(new IllegalStateException("boom"));

        job().run();

        stamped("ERROR", null);
    }

    @Test
    void thePasswordIsNeverPartOfWhatIsStamped() {
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new Found(Source.VAULT, "s3cret-pw", false));
        when(opener.open(any(), any(), any(), eq("s3cret-pw"), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(
                StatementExtractionResult.failure("ERROR: unreadable", StatementParseException.Kind.UNREADABLE), true));

        job().run();

        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(statements, org.mockito.Mockito.atLeastOnce()).markReparse(any(), anyInt(), any(), any(), summary.capture());
        assertThat(summary.getAllValues()).noneMatch(s -> s != null && s.contains("s3cret-pw"));
    }
}
