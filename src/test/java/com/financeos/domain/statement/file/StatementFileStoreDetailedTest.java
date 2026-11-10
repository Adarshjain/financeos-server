package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.crypto.EncryptionService;
import com.financeos.domain.statement.file.StatementFileStore.StoreOutcome;
import com.financeos.domain.statement.file.StatementFileStore.StoreResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.unit.DataSize;

/** {@link StatementFileStore#storeDetailed} names each way a store can end; {@code store} stays the id-only view of it. */
class StatementFileStoreDetailedTest {

    private final UUID user = UUID.randomUUID();
    private StatementFileRepository repo;
    private StatementFileProperties props;
    private StatementFileStore store;

    @BeforeEach
    void setUp() {
        repo = mock(StatementFileRepository.class);
        EncryptionService enc = new EncryptionService();
        ReflectionTestUtils.setField(enc, "base64Key", EncryptionService.generateKey());
        enc.init();
        props = new StatementFileProperties();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store = new StatementFileStore(repo, enc, props, new StatementFileMetrics(new SimpleMeterRegistry(), repo), tm,
                Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC));
        when(repo.findIdByUserIdAndSha256(any(), anyString())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(StatementFile.class))).thenAnswer(i -> {
            StatementFile f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            return f;
        });
    }

    private static byte[] pdf(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        byte[] h = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(h, 0, b, 0, h.length);
        return b;
    }

    @Test
    void storedHasAnId() {
        StoreResult r = store.storeDetailed(user, pdf(100), "a.pdf");

        assertEquals(StoreOutcome.STORED, r.outcome());
        assertNotNull(r.id());
    }

    @Test
    void aFileAlreadyStoredIsDedupedWithItsId() {
        UUID existing = UUID.randomUUID();
        when(repo.findIdByUserIdAndSha256(any(), anyString())).thenReturn(Optional.of(existing));

        StoreResult r = store.storeDetailed(user, pdf(100), "a.pdf");

        assertEquals(StoreOutcome.DEDUPED, r.outcome());
        assertEquals(existing, r.id());
    }

    @Test
    void disabledIsDisabled() {
        props.setEnabled(false);
        assertEquals(StoreOutcome.DISABLED, store.storeDetailed(user, pdf(100), "a.pdf").outcome());
    }

    @Test
    void emptyAndNullAreEmpty() {
        assertEquals(StoreOutcome.EMPTY, store.storeDetailed(user, new byte[0], "a.pdf").outcome());
        assertEquals(StoreOutcome.EMPTY, store.storeDetailed(user, null, "a.pdf").outcome());
    }

    @Test
    void overTheFileLimitIsTooLarge() {
        props.setMaxFileBytes(DataSize.ofBytes(1000));
        assertEquals(StoreOutcome.TOO_LARGE, store.storeDetailed(user, pdf(1001), "a.pdf").outcome());
    }

    @Test
    void anUnknownTypeIsUnsupported() {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89; png[1] = 'P'; png[2] = 'N'; png[3] = 'G';
        StoreResult r = store.storeDetailed(user, png, "a.png");
        assertEquals(StoreOutcome.UNSUPPORTED, r.outcome());
        assertNull(r.id());
    }

    @Test
    void userQuotaIsUserQuota() {
        props.setMaxBytesPerUser(DataSize.ofBytes(150));
        when(repo.sumSizeBytesByUserId(user)).thenReturn(100L);
        assertEquals(StoreOutcome.USER_QUOTA, store.storeDetailed(user, pdf(100), "a.pdf").outcome());
    }

    @Test
    void totalCapIsTotalCap() {
        props.setMaxTotalBytes(DataSize.ofBytes(150));
        when(repo.sumSizeBytes()).thenReturn(100L);
        assertEquals(StoreOutcome.TOTAL_CAP, store.storeDetailed(user, pdf(100), "a.pdf").outcome());
    }

    @Test
    void aDatabaseFailureIsErrorAndNeverThrows() {
        when(repo.saveAndFlush(any(StatementFile.class))).thenThrow(new IllegalStateException("db down"));
        assertEquals(StoreOutcome.ERROR, store.storeDetailed(user, pdf(100), "a.pdf").outcome());
    }

    @Test
    void storeStillReturnsTheIdOrEmpty() {
        assertEquals(true, store.store(user, pdf(100), "a.pdf").isPresent());
        props.setEnabled(false);
        assertEquals(true, store.store(user, pdf(100), "a.pdf").isEmpty());
    }
}
