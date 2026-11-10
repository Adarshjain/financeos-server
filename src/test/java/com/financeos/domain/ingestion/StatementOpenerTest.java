package com.financeos.domain.ingestion;

import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.parser.StatementParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared password order: explicit, else the account's, else the vault's; saving a working password is opt-in. */
class StatementOpenerTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";
    private final byte[] bytes = "f".getBytes();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private StatementParser parser;
    private StatementPasswordVault vault;
    private StatementOpener opener;
    private final StatementExtractionResult ok = StatementExtractionResult.success(List.of(), "1", null, null, null);
    private final StatementExtractionResult locked = StatementExtractionResult.failure(LOCKED, StatementParseException.Kind.LOCKED);

    @BeforeEach
    void setUp() {
        parser = mock(StatementParser.class);
        vault = mock(StatementPasswordVault.class);
        opener = new StatementOpener(parser, vault);
    }

    @Test
    void anOpenFileNeedsNoPassword() {
        when(parser.parse(bytes, null)).thenReturn(ok);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, null, true);

        assertThat(o.result()).isSameAs(ok);
        assertThat(o.passwordTried()).isFalse();
        verify(vault, never()).savedPasswords(any());
    }

    @Test
    void theAccountsSavedPasswordOpensItFirst() {
        when(parser.parse(bytes, "acct")).thenReturn(ok);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, "acct", false);

        assertThat(o.result().success()).isTrue();
        assertThat(o.passwordTried()).isTrue();
        verify(vault, never()).savedPasswords(any());
    }

    @Test
    void theExplicitPasswordIsTriedBeforeTheAccountsAndThenTheAccountsIsTriedAmongTheOthers() {
        when(parser.parse(bytes, "typed")).thenReturn(locked);
        when(parser.parse(bytes, "acct")).thenReturn(ok);
        when(vault.savedPasswords(userId)).thenReturn(List.of());

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, "typed", "acct", true);

        assertThat(o.result()).isSameAs(ok);
        verify(vault, never()).saveIfMissing(any(), any(), any());
    }

    @Test
    void aLockedFileTriesTheVaultsOtherPasswordsAndNeverRepeatsTheFirst() {
        when(parser.parse(bytes, "acct")).thenReturn(locked);
        when(vault.savedPasswords(userId)).thenReturn(List.of("acct", "other"));
        when(parser.parse(bytes, "other")).thenReturn(ok);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, "acct", false);

        assertThat(o.result()).isSameAs(ok);
        verify(parser, org.mockito.Mockito.times(1)).parse(bytes, "acct");
    }

    @Test
    void aWorkingVaultPasswordIsSavedToAnAccountWithNoneOnlyWhenAsked() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(vault.savedPasswords(userId)).thenReturn(List.of("other"));
        when(parser.parse(bytes, "other")).thenReturn(ok);

        opener.open(userId, accountId, bytes, null, null, true);
        verify(vault).saveIfMissing(userId, accountId, "other");
    }

    @Test
    void theReadOnlyModeNeverSavesAPassword() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(vault.savedPasswords(userId)).thenReturn(List.of("other"));
        when(parser.parse(bytes, "other")).thenReturn(ok);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, null, false);

        assertThat(o.result().success()).isTrue();
        verify(vault, never()).saveIfMissing(any(), any(), any());
        verify(vault, never()).save(any(), any(), any());
    }

    @Test
    void anAccountWithAPasswordIsNeverOverwrittenByAVaultOne() {
        when(parser.parse(bytes, "acct")).thenReturn(locked);
        when(vault.savedPasswords(userId)).thenReturn(List.of("other"));
        when(parser.parse(bytes, "other")).thenReturn(ok);

        opener.open(userId, accountId, bytes, null, "acct", true);

        verify(vault, never()).saveIfMissing(any(), any(), any());
    }

    @Test
    void noPasswordWorkingLeavesTheLockedResultAndPasswordTried() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(vault.savedPasswords(userId)).thenReturn(List.of("a", "b"));
        when(parser.parse(bytes, "a")).thenReturn(locked);
        when(parser.parse(bytes, "b")).thenReturn(locked);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, null, false);

        assertThat(o.result()).isSameAs(locked);
        assertThat(o.passwordTried()).isTrue();
    }

    @Test
    void aFailureThatIsNotAboutThePasswordNeverReachesTheVault() {
        StatementExtractionResult bad = StatementExtractionResult.failure("ERROR: corrupt", StatementParseException.Kind.UNREADABLE);
        when(parser.parse(eq(bytes), isNull())).thenReturn(bad);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, null, true);

        assertThat(o.result()).isSameAs(bad);
        verify(vault, never()).savedPasswords(any());
    }

    @Test
    void aHandBuiltOpenerWithoutAVaultStillReturnsTheLockedResult() {
        StatementOpener bare = new StatementOpener(parser, null);
        when(parser.parse(bytes, null)).thenReturn(locked);

        assertThat(bare.open(userId, accountId, bytes, null, null, true).result()).isSameAs(locked);
    }
}
