package com.financeos.domain.ingestion;

import com.financeos.domain.ingestion.StatementPasswordFinder.Found;
import com.financeos.domain.ingestion.StatementPasswordFinder.Source;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.parser.StatementParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The upload path's wider search: after the typed / saved / vault passwords, the finder (made-up profile Riya Sharma, 1994-08-14). */
class StatementOpenerSearchTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";
    private final byte[] bytes = "%PDF-f".getBytes();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private StatementParser parser;
    private StatementPasswordVault vault;
    private StatementPasswordFinder finder;
    private StatementOpener opener;
    private final StatementExtractionResult ok = StatementExtractionResult.success(List.of(), "1", null, null, null);
    private final StatementExtractionResult locked = StatementExtractionResult.failure(LOCKED, StatementParseException.Kind.LOCKED);

    @BeforeEach
    void setUp() {
        parser = mock(StatementParser.class);
        vault = mock(StatementPasswordVault.class);
        finder = mock(StatementPasswordFinder.class);
        opener = new StatementOpener(parser, vault, finder);
        when(vault.savedPasswords(userId)).thenReturn(List.of());
    }

    @Test
    void aBankRulePasswordOpensItAndIsSavedOnAnAccountWithNone() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(finder.find(eq(userId), eq(accountId), eq(bytes), eq(true), anySet()))
                .thenReturn(new Found(Source.BANK_RULES, "rule-pw", false));
        when(parser.parse(bytes, "rule-pw")).thenReturn(ok);

        StatementOpener.Opened o = opener.openWithSearch(userId, accountId, bytes, null, null);

        assertThat(o.result()).isSameAs(ok);
        assertThat(o.passwordTried()).isTrue();
        verify(vault).saveIfMissing(userId, accountId, "rule-pw");
    }

    @Test
    void anotherAccountsPasswordFoundByTheFinderIsSavedToo() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(finder.find(any(), any(), any(), anyBoolean(), anySet()))
                .thenReturn(new Found(Source.OTHER_ACCOUNT, "rule-pw", false));
        when(parser.parse(bytes, "rule-pw")).thenReturn(ok);

        opener.openWithSearch(userId, accountId, bytes, null, null);

        verify(vault).saveIfMissing(userId, accountId, "rule-pw");
    }

    @Test
    void anExistingWrongAccountPasswordIsNotOverwritten() {
        when(parser.parse(bytes, "wrong")).thenReturn(locked);
        when(finder.find(any(), any(), any(), anyBoolean(), anySet()))
                .thenReturn(new Found(Source.BANK_RULES, "rule-pw", false));
        when(parser.parse(bytes, "rule-pw")).thenReturn(ok);

        StatementOpener.Opened o = opener.openWithSearch(userId, accountId, bytes, null, "wrong");

        assertThat(o.result()).isSameAs(ok);
        verify(vault, never()).saveIfMissing(any(), any(), any());
        verify(vault, never()).save(any(), any(), any());
        verify(finder).find(userId, accountId, bytes, true, Set.of("wrong"));
    }

    @Test
    void whenNothingOpensItTheFailureIsUnchanged() {
        when(parser.parse(bytes, null)).thenReturn(locked);
        when(finder.find(any(), any(), any(), anyBoolean(), anySet())).thenReturn(new Found(null, null, false));

        StatementOpener.Opened o = opener.openWithSearch(userId, accountId, bytes, null, null);

        assertThat(o.result()).isSameAs(locked);
        assertThat(o.result().failureReason()).isEqualTo(LOCKED);
        verify(vault, never()).saveIfMissing(any(), any(), any());
    }

    @Test
    void aCorrectTypedPasswordNeverCallsTheFinder() {
        when(parser.parse(bytes, "typed")).thenReturn(ok);

        StatementOpener.Opened o = opener.openWithSearch(userId, accountId, bytes, "typed", null);

        assertThat(o.result()).isSameAs(ok);
        verifyNoInteractions(finder);
    }

    @Test
    void aNonPasswordFailureNeverCallsTheFinder() {
        StatementExtractionResult bad = StatementExtractionResult.failure("ERROR: corrupt", StatementParseException.Kind.UNREADABLE);
        when(parser.parse(eq(bytes), isNull())).thenReturn(bad);

        StatementOpener.Opened o = opener.openWithSearch(userId, accountId, bytes, null, null);

        assertThat(o.result()).isSameAs(bad);
        verifyNoInteractions(finder);
    }

    @Test
    void thePlainOpenUsedByTheRereadJobNeverCallsTheFinderOrSaves() {
        when(parser.parse(bytes, null)).thenReturn(locked);

        StatementOpener.Opened o = opener.open(userId, accountId, bytes, null, null, false);

        assertThat(o.result()).isSameAs(locked);
        verifyNoInteractions(finder);
        verify(vault, never()).saveIfMissing(any(), any(), any());
    }
}
