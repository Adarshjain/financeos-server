package com.financeos.domain.statement.file;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.core.crypto.EncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StatementFileStoreTest {

    private final UUID user = UUID.randomUUID();
    private StatementFileRepository repo;
    private EncryptionService enc;
    private StatementFileProperties props;
    private MeterRegistry registry;
    private PlatformTransactionManager tm;
    private StatementFileStore store;
    private ListAppender<ILoggingEvent> logs;
    private final Instant now = Instant.parse("2026-11-10T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(StatementFileRepository.class);
        enc = new EncryptionService();
        ReflectionTestUtils.setField(enc, "base64Key", EncryptionService.generateKey());
        enc.init();
        props = new StatementFileProperties();
        registry = new SimpleMeterRegistry();
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store = new StatementFileStore(repo, enc, props, new StatementFileMetrics(registry, repo), tm, clock);
        when(repo.findIdByUserIdAndSha256(any(), anyString())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(StatementFile.class))).thenAnswer(i -> {
            StatementFile f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(StatementFileStore.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(StatementFileStore.class)).detachAppender(logs);
    }

    private static byte[] pdf(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        byte[] h = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(h, 0, b, 0, h.length);
        return b;
    }

    private static byte[] zip(int size) {
        byte[] b = new byte[size];
        b[0] = 'P'; b[1] = 'K'; b[2] = 3; b[3] = 4;
        return b;
    }

    private static byte[] ole(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0xD0; b[1] = (byte) 0xCF; b[2] = 0x11; b[3] = (byte) 0xE0;
        return b;
    }

    private double count(String outcome, String reason) {
        var c = reason == null
                ? registry.find("financeos.statement.files").tag("outcome", outcome).counter()
                : registry.find("financeos.statement.files").tag("outcome", outcome).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private void assertSkipped(String reason) {
        assertEquals(1, count("skipped", reason));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void disabledStoresNothing() {
        props.setEnabled(false);
        assertTrue(store.store(user, pdf(100), "a.pdf").isEmpty());
        assertSkipped("disabled");
        verifyNoInteractions(tm);
    }

    @Test
    void nullAndEmptyBytesAreSkipped() {
        assertTrue(store.store(user, null, "a.pdf").isEmpty());
        assertTrue(store.store(user, new byte[0], "a.pdf").isEmpty());
        assertEquals(2, count("skipped", "empty"));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void fileAtTheLimitIsStoredAndOneByteOverIsRejected() {
        props.setMaxFileBytes(DataSize.ofBytes(1000));
        assertTrue(store.store(user, pdf(1000), "a.pdf").isPresent());
        assertTrue(store.store(user, pdf(1001), "a.pdf").isEmpty());
        assertEquals(1, count("skipped", "too_large"));
        verify(repo, times(1)).saveAndFlush(any());
    }

    @Test
    void unsupportedTypeIsSkipped() {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        assertTrue(store.store(user, png, "a.png").isEmpty());
        assertSkipped("unsupported");
    }

    @Test
    void tinyFileIsUnsupported() {
        assertTrue(store.store(user, new byte[]{1, 2, 3}, "a.pdf").isEmpty());
        assertSkipped("unsupported");
    }

    @Test
    void detectsPdfXlsxAndXlsTypes() {
        for (byte[] b : new byte[][]{pdf(50), zip(50), ole(50)}) {
            assertTrue(store.store(user, b, null).isPresent());
        }
        ArgumentCaptor<StatementFile> cap = ArgumentCaptor.forClass(StatementFile.class);
        verify(repo, times(3)).saveAndFlush(cap.capture());
        assertEquals("pdf", cap.getAllValues().get(0).getFileType());
        assertEquals("xlsx", cap.getAllValues().get(1).getFileType());
        assertEquals("xls", cap.getAllValues().get(2).getFileType());
        assertNull(cap.getAllValues().get(0).getOriginalName());
    }

    @Test
    void sameFileAlreadyStoredReturnsExistingIdWithoutWriting() {
        UUID existing = UUID.randomUUID();
        byte[] b = pdf(100);
        when(repo.findIdByUserIdAndSha256(user, StatementFileStore.sha256Hex(b))).thenReturn(Optional.of(existing));
        assertEquals(Optional.of(existing), store.store(user, b, "a.pdf"));
        verify(repo, never()).saveAndFlush(any());
        verify(repo, never()).sumSizeBytes();
        assertEquals(1, count("deduped", null));
        assertEquals(0, count("stored", null));
    }

    @Test
    void userQuotaBoundary() {
        props.setMaxBytesPerUser(DataSize.ofBytes(1000));
        when(repo.sumSizeBytesByUserId(user)).thenReturn(900L);
        assertTrue(store.store(user, pdf(100), "a.pdf").isPresent());
        assertTrue(store.store(user, pdf(101), "b.pdf").isEmpty());
        assertEquals(1, count("skipped", "user_quota"));
        verify(repo, times(1)).saveAndFlush(any());
    }

    @Test
    void totalCapBoundaryAndErrorEvent() {
        props.setMaxTotalBytes(DataSize.ofBytes(10_000));
        when(repo.sumSizeBytes()).thenReturn(9_900L);
        assertTrue(store.store(user, pdf(100), "a.pdf").isPresent());
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("STATEMENT_FILE_STORE_FULL")));

        assertTrue(store.store(user, pdf(101), "secret-name.pdf").isEmpty());
        assertEquals(1, count("skipped", "total_cap"));
        var full = logs.list.stream().filter(e -> e.getFormattedMessage().contains("STATEMENT_FILE_STORE_FULL")).toList();
        assertEquals(1, full.size());
        assertEquals(Level.ERROR, full.get(0).getLevel());
        assertFalse(full.get(0).getFormattedMessage().contains("secret-name"));
        verify(repo, times(1)).saveAndFlush(any());
    }

    @Test
    void storesEncryptedBytesThatDecryptToTheOriginal() {
        byte[] b = pdf(300);
        Optional<UUID> id = store.store(user, b, "/tmp/uploads/Statement Jan.pdf");
        assertTrue(id.isPresent());
        ArgumentCaptor<StatementFile> cap = ArgumentCaptor.forClass(StatementFile.class);
        verify(repo).saveAndFlush(cap.capture());
        StatementFile f = cap.getValue();
        assertEquals(user, f.getUserId());
        assertEquals(StatementFileStore.sha256Hex(b), f.getSha256());
        assertEquals(300, f.getSizeBytes());
        assertEquals("pdf", f.getFileType());
        assertEquals("Statement Jan.pdf", f.getOriginalName());
        assertEquals(1, f.getEncVersion());
        assertFalse(Arrays.equals(b, f.getData()));
        assertArrayEquals(b, enc.decryptBytes(f.getData(), StatementFileStore.aad(user, f.getSha256())));
        assertThrows(RuntimeException.class, () -> enc.decryptBytes(f.getData()));
        assertEquals(1, count("stored", null));
    }

    @Test
    void writeRunsInItsOwnTransaction() {
        store.store(user, pdf(100), "a.pdf");
        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(tm).getTransaction(def.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, def.getValue().getPropagationBehavior());
        verify(tm).commit(any());
    }

    @Test
    void exceptionInTheWriteReturnsEmptyAndRollsBack() {
        when(repo.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        assertTrue(store.store(user, pdf(100), "a.pdf").isEmpty());
        assertEquals(1, count("skipped", "error"));
        verify(tm).rollback(any());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void exceptionBeforeTheWriteReturnsEmpty() {
        when(repo.sumSizeBytesByUserId(any())).thenThrow(new IllegalStateException("db down"));
        assertTrue(store.store(user, pdf(100), "a.pdf").isEmpty());
        assertEquals(1, count("skipped", "error"));
    }

    @Test
    void encryptionFailureReturnsEmpty() {
        EncryptionService broken = mock(EncryptionService.class);
        when(broken.encryptBytes(any(), any())).thenThrow(new RuntimeException("boom"));
        StatementFileStore s = new StatementFileStore(repo, broken, props, new StatementFileMetrics(registry, repo), tm, clock);
        assertTrue(s.store(user, pdf(100), "a.pdf").isEmpty());
        assertEquals(1, count("skipped", "error"));
    }

    @Test
    void uniqueKeyRaceResolvesToTheExistingId() {
        UUID winner = UUID.randomUUID();
        when(repo.findIdByUserIdAndSha256(any(), anyString()))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_stmt_files_user_sha"));
        assertEquals(Optional.of(winner), store.store(user, pdf(100), "a.pdf"));
        assertEquals(1, count("deduped", null));
        assertEquals(0, count("skipped", "error"));
    }

    @Test
    void integrityViolationWithNoWinnerIsAnError() {
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("other"));
        assertTrue(store.store(user, pdf(100), "a.pdf").isEmpty());
        assertEquals(1, count("skipped", "error"));
    }

    // ---- load ----

    private StatementFile stored(byte[] plain, String sha) {
        StatementFile f = new StatementFile();
        f.setId(UUID.randomUUID());
        f.setUserId(user);
        f.setSha256(sha);
        f.setData(enc.encryptBytes(plain, StatementFileStore.aad(user, sha)));
        return f;
    }

    @Test
    void loadReturnsDecryptedOriginal() {
        byte[] b = pdf(200);
        StatementFile f = stored(b, StatementFileStore.sha256Hex(b));
        when(repo.findByIdAndUserId(f.getId(), user)).thenReturn(Optional.of(f));
        assertArrayEquals(b, store.load(user, f.getId()).orElseThrow());
    }

    @Test
    void loadForAnotherUserIsEmpty() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(repo.findByIdAndUserId(id, other)).thenReturn(Optional.empty());
        assertTrue(store.load(other, id).isEmpty());
        verify(repo).findByIdAndUserId(id, other);
    }

    @Test
    void loadUnknownIdIsEmpty() {
        when(repo.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertTrue(store.load(user, UUID.randomUUID()).isEmpty());
    }

    @Test
    void loadWithShaMismatchIsEmptyAndLogsCorrupt() {
        byte[] b = pdf(200);
        StatementFile f = stored(b, "0".repeat(64));
        when(repo.findByIdAndUserId(f.getId(), user)).thenReturn(Optional.of(f));
        assertTrue(store.load(user, f.getId()).isEmpty());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("STATEMENT_FILE_CORRUPT")));
    }

    @Test
    void loadWithUndecryptableDataIsEmptyAndLogsCorrupt() {
        StatementFile f = stored(pdf(200), "x");
        f.setData(new byte[40]);
        when(repo.findByIdAndUserId(f.getId(), user)).thenReturn(Optional.of(f));
        assertTrue(store.load(user, f.getId()).isEmpty());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("STATEMENT_FILE_CORRUPT")));
    }

    // ---- delete / totals ----

    @Test
    void deleteOrphansDelegates() {
        when(repo.deleteOrphansByUserId(eq(user), any())).thenReturn(3);
        assertEquals(3, store.deleteOrphans(user));
    }

    @Test
    void deleteIfUnreferencedReportsWhetherARowWasDeleted() {
        UUID id = UUID.randomUUID();
        when(repo.deleteByIdIfUnreferenced(eq(id), eq(user), any())).thenReturn(1);
        assertTrue(store.deleteIfUnreferenced(user, id));
        when(repo.deleteByIdIfUnreferenced(eq(id), eq(user), any())).thenReturn(0);
        assertFalse(store.deleteIfUnreferenced(user, id));
    }

    @Test
    void totalsDelegate() {
        when(repo.sumSizeBytes()).thenReturn(42L);
        when(repo.sumSizeBytesByUserId(user)).thenReturn(7L);
        assertEquals(42, store.totalBytes());
        assertEquals(7, store.bytesForUser(user));
    }

    // ---- names ----

    @Test
    void baseNameRules() {
        assertNull(StatementFileStore.baseName(null));
        assertNull(StatementFileStore.baseName("   "));
        assertNull(StatementFileStore.baseName("dir/"));
        assertEquals("a.pdf", StatementFileStore.baseName("  a.pdf "));
        assertEquals("a.pdf", StatementFileStore.baseName("C:\\x\\y\\a.pdf"));
        assertEquals("a.pdf", StatementFileStore.baseName("../../etc/a.pdf"));
        assertEquals(255, StatementFileStore.baseName("p/" + "n".repeat(400)).length());
    }

    // ---- metrics gauges ----

    @Test
    void gaugesAreCachedForTenMinutesAndNeverThrow() {
        StatementFileRepository r = mock(StatementFileRepository.class);
        AtomicLong clk = new AtomicLong(0);
        MeterRegistry reg = new SimpleMeterRegistry();
        new StatementFileMetrics(reg, r, clk::get);
        when(r.sumSizeBytes()).thenReturn(100L);
        when(r.count()).thenReturn(2L);
        var bytes = reg.find("financeos.statement.files.bytes").gauge();
        var cnt = reg.find("financeos.statement.files.count").gauge();

        assertEquals(100, bytes.value());
        assertEquals(2, cnt.value());
        when(r.sumSizeBytes()).thenReturn(200L);
        clk.set(StatementFileMetrics.GAUGE_TTL_NANOS - 1);
        assertEquals(100, bytes.value());
        verify(r, times(1)).sumSizeBytes();

        clk.set(StatementFileMetrics.GAUGE_TTL_NANOS);
        assertEquals(200, bytes.value());

        when(r.sumSizeBytes()).thenThrow(new IllegalStateException("db down"));
        clk.set(2 * StatementFileMetrics.GAUGE_TTL_NANOS);
        assertEquals(200, bytes.value());
    }

    @Test
    void gaugeIsNaNUntilFirstSuccessfulRead() {
        StatementFileRepository r = mock(StatementFileRepository.class);
        MeterRegistry reg = new SimpleMeterRegistry();
        new StatementFileMetrics(reg, r, () -> 0L);
        when(r.count()).thenThrow(new IllegalStateException("db down"));
        assertTrue(Double.isNaN(reg.find("financeos.statement.files.count").gauge().value()));
    }

    @Test
    void failedReadIsRetriedAfterOneMinuteNotTen() {
        StatementFileRepository r = mock(StatementFileRepository.class);
        AtomicLong clk = new AtomicLong(0);
        MeterRegistry reg = new SimpleMeterRegistry();
        new StatementFileMetrics(reg, r, clk::get);
        when(r.count()).thenThrow(new IllegalStateException("db down")).thenReturn(5L);
        var g = reg.find("financeos.statement.files.count").gauge();
        assertTrue(Double.isNaN(g.value()));
        clk.set(StatementFileMetrics.GAUGE_RETRY_NANOS - 1);
        assertTrue(Double.isNaN(g.value()));
        verify(r, times(1)).count();
        clk.set(StatementFileMetrics.GAUGE_RETRY_NANOS);
        assertEquals(5, g.value());
        verify(r, times(2)).count();
    }

    @Test
    void concurrentScrapeDoesNotWaitForASlowQuery() throws Exception {
        java.util.concurrent.CountDownLatch inQuery = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicLong calls = new AtomicLong();
        StatementFileMetrics.CachedLong c = new StatementFileMetrics.CachedLong(() -> {
            calls.incrementAndGet();
            inQuery.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return 9L;
        }, () -> 0L, 1000, 1000);
        Thread t = new Thread(c::get);
        t.start();
        assertTrue(inQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(Double.isNaN(c.get()));
        assertEquals(1, calls.get());
        release.countDown();
        t.join(5000);
        assertEquals(9, c.get());
    }

    @Test
    void gaugesAreStronglyReferencedAndSurviveGc() {
        StatementFileRepository r = mock(StatementFileRepository.class);
        when(r.count()).thenReturn(3L);
        MeterRegistry reg = new SimpleMeterRegistry();
        StatementFileMetrics m = new StatementFileMetrics(reg, r);
        System.gc();
        assertEquals(3, reg.find("financeos.statement.files.count").gauge().value());
        assertNotNull(m);
    }

    // ---- transaction boundary ----

    @Test
    void everyRepositoryCallRunsInsideANewTransaction() {
        AtomicInteger depth = new AtomicInteger();
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenAnswer(i -> { depth.incrementAndGet(); return new SimpleTransactionStatus(); });
        doAnswer(i -> { depth.decrementAndGet(); return null; }).when(ptm).commit(any());
        doAnswer(i -> { depth.decrementAndGet(); return null; }).when(ptm).rollback(any());
        AtomicBoolean enforce = new AtomicBoolean();
        AtomicInteger outside = new AtomicInteger();
        StatementFileRepository r = mock(StatementFileRepository.class, withSettings().defaultAnswer(inv -> {
            if (enforce.get() && depth.get() == 0) {
                outside.incrementAndGet();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
        }));
        UUID winner = UUID.randomUUID();
        when(r.findIdByUserIdAndSha256(any(), anyString())).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(r.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("race"));
        enforce.set(true);
        StatementFileStore s = new StatementFileStore(r, enc, props, new StatementFileMetrics(registry, r), ptm, clock);
        assertEquals(Optional.of(winner), s.store(user, pdf(100), "a.pdf"));
        // happy path too
        doAnswer(i -> { StatementFile f = i.getArgument(0); f.setId(UUID.randomUUID()); return f; }).when(r).saveAndFlush(any());
        doReturn(Optional.empty()).when(r).findIdByUserIdAndSha256(any(), anyString());
        assertTrue(s.store(user, pdf(101), "b.pdf").isPresent());
        assertEquals(0, outside.get(), "repository calls outside a new transaction");
        assertEquals(0, depth.get());
    }

    @Test
    void lookupErrorDoesNotEscapeAndRollsBackOnlyTheNewTransaction() {
        when(repo.findIdByUserIdAndSha256(any(), anyString())).thenThrow(new IllegalStateException("db down"));
        assertTrue(store.store(user, pdf(100), "a.pdf").isEmpty());
        verify(tm).rollback(any());
        assertEquals(1, count("skipped", "error"));
    }

    // ---- orphan grace ----

    @Test
    void deleteOrphansOnlyTouchesFilesOlderThanOneHour() {
        store.deleteOrphans(user);
        verify(repo).deleteOrphansByUserId(user, now.minus(Duration.ofHours(1)));

        StatementFileStore later = new StatementFileStore(repo, enc, props, new StatementFileMetrics(registry, repo), tm,
                Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
        later.deleteOrphans(user);
        verify(repo).deleteOrphansByUserId(user, now.plusSeconds(60).minus(Duration.ofHours(1)));
    }

    @Test
    void deleteIfUnreferencedUsesTheSameGrace() {
        UUID id = UUID.randomUUID();
        store.deleteIfUnreferenced(user, id);
        verify(repo).deleteByIdIfUnreferenced(id, user, now.minus(Duration.ofHours(1)));
    }

    // ---- AAD binding ----

    @Test
    void blobMovedToAnotherUsersRowFailsToLoad() {
        byte[] b = pdf(200);
        String sha = StatementFileStore.sha256Hex(b);
        StatementFile f = stored(b, sha);
        UUID other = UUID.randomUUID();
        f.setUserId(other);
        when(repo.findByIdAndUserId(f.getId(), other)).thenReturn(Optional.of(f));
        assertTrue(store.load(other, f.getId()).isEmpty());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("STATEMENT_FILE_CORRUPT")));
    }

    @Test
    void blobMovedToARowWithAnotherShaFailsToLoad() {
        byte[] b = pdf(200);
        StatementFile f = stored(b, StatementFileStore.sha256Hex(b));
        f.setSha256("a".repeat(64));
        when(repo.findByIdAndUserId(f.getId(), user)).thenReturn(Optional.of(f));
        assertTrue(store.load(user, f.getId()).isEmpty());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("STATEMENT_FILE_CORRUPT")));
    }

    @Test
    void aadIsUserIdColonSha() {
        assertArrayEquals((user + ":abc").getBytes(StandardCharsets.UTF_8), StatementFileStore.aad(user, "abc"));
    }
}
