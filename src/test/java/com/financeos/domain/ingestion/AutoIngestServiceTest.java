package com.financeos.domain.ingestion;

import com.financeos.domain.ingestion.StatementAccountMatcher.AccountKey;
import com.financeos.domain.ingestion.held.HeldUpload;
import com.financeos.domain.ingestion.held.HeldUploadService;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class AutoIngestServiceTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";

    private StatementIdentifier identifier;
    private StatementPasswordVault vault;
    private StatementAccountMatcher matcher;
    private FileIngestionService ingestion;
    private HeldUploadService held;
    private AutoIngestService service;
    private JobExecutionContext ctx;
    private UUID userId;
    private UUID jobId;
    private final UUID hdfcOne = UUID.randomUUID();
    private final UUID hdfcTwo = UUID.randomUUID();
    private final UUID icici = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        identifier = mock(StatementIdentifier.class);
        vault = mock(StatementPasswordVault.class);
        matcher = mock(StatementAccountMatcher.class);
        ingestion = mock(FileIngestionService.class);
        held = mock(HeldUploadService.class);
        service = new AutoIngestService(identifier, vault, matcher, ingestion, held);
        userId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.getJobId()).thenReturn(jobId);
        when(matcher.keysOf(userId)).thenReturn(List.of(
                new AccountKey(hdfcOne, "hdfc", Set.of("1111")),
                new AccountKey(hdfcTwo, "hdfc", Set.of("2222")),
                new AccountKey(icici, "icici", Set.of("2222"))));
        when(vault.savedPasswords(userId)).thenReturn(List.of());
        when(held.hold(any(), any(), anyInt(), any(), any(), any(), any())).thenAnswer(inv -> {
            HeldUpload h = new HeldUpload();
            h.setId(UUID.randomUUID());
            return h;
        });
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private JobArtifact artifact(String name, String content) {
        JobArtifact a = new JobArtifact();
        a.setId(UUID.randomUUID());
        a.setJobId(jobId);
        a.setFilename(name);
        a.setContentType("application/pdf");
        a.setData(content.getBytes());
        return a;
    }

    private static FileIngestionResult ok(String name, int created) {
        return new FileIngestionResult(1, created, 0,
                List.of(new FileIngestionResult.FileSummary(name, "SUCCESS", created, null, null, created, 0)));
    }

    @Test
    void oneMatchIsIngestedIntoThatAccount() {
        JobArtifact a = artifact("s.pdf", "one");
        when(identifier.identify(a.getData(), null)).thenReturn(new StatementIdentity("hdfc", "HDFC Bank", "XXXX 1111"));
        when(ingestion.ingest(eq(hdfcOne), anyList(), eq(ctx), nullable(String.class))).thenReturn(ok("s.pdf", 5));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        assertThat(r.fileDetails()).hasSize(1);
        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        assertThat(r.totalCreated()).isEqualTo(5);
        verifyNoInteractions(held);
    }

    @Test
    void noMatchIsHeldWithTheDetectedBankAndLastFour() {
        JobArtifact a = artifact("s.pdf", "none");
        when(identifier.identify(a.getData(), null)).thenReturn(new StatementIdentity("axis", "Axis", "XXXX 9999"));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        verify(held).hold(userId, a, 0, "axis", "9999", HeldUpload.Reason.NO_MATCH, List.of());
        FileIngestionResult.FileSummary file = r.fileDetails().get(0);
        assertThat(file.status()).isEqualTo("HELD");
        assertThat(file.heldUploadId()).isNotNull();
        verify(ingestion, never()).ingest(any(), anyList(), any(), any());
    }

    @Test
    void sameBankSameLastFourOnTwoAccountsIsAmbiguous() {
        // hdfc 2222 matches only hdfcTwo; make a second hdfc account carry 2222 too
        when(matcher.keysOf(userId)).thenReturn(List.of(
                new AccountKey(hdfcOne, "hdfc", Set.of("2222")),
                new AccountKey(hdfcTwo, "hdfc", Set.of("2222"))));
        JobArtifact a = artifact("s.pdf", "amb");
        when(identifier.identify(a.getData(), null)).thenReturn(new StatementIdentity("hdfc", "HDFC", "XXXX 2222"));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        verify(held).hold(eq(userId), eq(a), eq(0), eq("hdfc"), eq("2222"), eq(HeldUpload.Reason.AMBIGUOUS),
                eq(List.of(hdfcOne, hdfcTwo)));
        assertThat(r.fileDetails().get(0).status()).isEqualTo("HELD");
    }

    @Test
    void lockedFileThatNoSavedPasswordOpensIsHeldAsLocked() {
        JobArtifact a = artifact("s.pdf", "locked");
        when(identifier.identify(any(), any())).thenThrow(new StatementParseException(LOCKED));
        when(vault.savedPasswords(userId)).thenReturn(List.of("p1", "p2"));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        verify(held).hold(userId, a, 0, null, null, HeldUpload.Reason.LOCKED, List.of());
        verify(identifier).identify(a.getData(), "p1");
        verify(identifier).identify(a.getData(), "p2");
        assertThat(r.fileDetails().get(0).status()).isEqualTo("HELD");
    }

    @Test
    void lockedFileOpenedByASavedPasswordIsMatchedLikeAnyOther() {
        JobArtifact a = artifact("s.pdf", "locked-but-known");
        when(identifier.identify(a.getData(), null)).thenThrow(new StatementParseException(LOCKED));
        when(vault.savedPasswords(userId)).thenReturn(List.of("known"));
        when(identifier.identify(a.getData(), "known")).thenReturn(new StatementIdentity("icici", "ICICI", "XXXX 2222"));
        when(ingestion.ingest(eq(icici), anyList(), eq(ctx), nullable(String.class))).thenReturn(ok("s.pdf", 2));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        verifyNoInteractions(held);
    }

    @Test
    void aTypedPasswordIsUsedForDetectionAndPassedToTheIngest() {
        JobArtifact a = artifact("s.pdf", "typed");
        when(identifier.identify(a.getData(), "typed")).thenReturn(new StatementIdentity("hdfc", "HDFC", "XXXX 1111"));
        when(ingestion.ingest(eq(hdfcOne), anyList(), eq(ctx), eq("typed"))).thenReturn(ok("s.pdf", 1));

        FileIngestionResult r = service.run(ctx, List.of(a), "typed");

        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void filesKeepTheirPositionAcrossAccountsAndHeldOnes() {
        JobArtifact first = artifact("a.pdf", "first");
        JobArtifact second = artifact("b.pdf", "second");
        JobArtifact third = artifact("c.pdf", "third");
        when(identifier.identify(first.getData(), null)).thenReturn(new StatementIdentity("hdfc", "HDFC", "1111"));
        when(identifier.identify(second.getData(), null)).thenReturn(new StatementIdentity("axis", "Axis", "7777"));
        when(identifier.identify(third.getData(), null)).thenReturn(new StatementIdentity("icici", "ICICI", "2222"));
        when(ingestion.ingest(eq(hdfcOne), anyList(), eq(ctx), nullable(String.class))).thenReturn(ok("a.pdf", 1));
        when(ingestion.ingest(eq(icici), anyList(), eq(ctx), nullable(String.class))).thenReturn(ok("c.pdf", 3));

        FileIngestionResult r = service.run(ctx, List.of(first, second, third), null);

        assertThat(r.fileDetails()).extracting(FileIngestionResult.FileSummary::filename).containsExactly("a.pdf", "b.pdf", "c.pdf");
        assertThat(r.fileDetails()).extracting(FileIngestionResult.FileSummary::status).containsExactly("SUCCESS", "HELD", "SUCCESS");
        assertThat(r.totalCreated()).isEqualTo(4);
    }

    @Test
    void anUnreadableFileFailsWithoutBeingHeld() {
        JobArtifact a = artifact("s.pdf", "junk");
        when(identifier.identify(any(), any())).thenThrow(new StatementParseException("ERROR: failed to read PDF."));

        FileIngestionResult r = service.run(ctx, List.of(a), null);

        FileIngestionResult.FileSummary file = r.fileDetails().get(0);
        assertThat(file.status()).isEqualTo("FAILED");
        assertThat(file.errorCode()).isEqualTo(FileIngestionResult.ErrorCode.UNREADABLE);
        verifyNoInteractions(held);
    }
}
