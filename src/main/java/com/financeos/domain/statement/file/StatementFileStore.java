package com.financeos.domain.statement.file;

import com.financeos.core.crypto.EncryptionService;
import com.financeos.statement.parser.FileType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the original statement file (exactly the bytes received) encrypted, one copy per (user, sha256), so a
 * statement can be re-parsed after a parser fix. {@link #store} never throws and never touches the caller's
 * transaction: the write runs in its own transaction.
 */
@Service
public class StatementFileStore {

    private static final Logger log = LoggerFactory.getLogger(StatementFileStore.class);
    private static final int MAX_NAME = 255;
    static final Duration ORPHAN_GRACE = Duration.ofHours(1);

    private final StatementFileRepository repository;
    private final EncryptionService encryption;
    private final StatementFileProperties props;
    private final StatementFileMetrics metrics;
    private final Clock clock;
    /** REQUIRES_NEW through a TransactionTemplate: the boundary is a real transaction manager call, not a self-invoked proxy. */
    private final TransactionTemplate newTx;

    @Autowired
    public StatementFileStore(StatementFileRepository repository, EncryptionService encryption,
                              StatementFileProperties props, StatementFileMetrics metrics,
                              PlatformTransactionManager txManager, ObjectProvider<Clock> clock) {
        this(repository, encryption, props, metrics, txManager, clock.getIfAvailable(Clock::systemUTC));
    }

    StatementFileStore(StatementFileRepository repository, EncryptionService encryption,
                       StatementFileProperties props, StatementFileMetrics metrics,
                       PlatformTransactionManager txManager, Clock clock) {
        this.repository = repository;
        this.encryption = encryption;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Outcome(UUID id, String outcome) {
        static Outcome stored(UUID id) { return new Outcome(id, "stored"); }
        static Outcome deduped(UUID id) { return new Outcome(id, "deduped"); }
        static Outcome skipped(String reason) { return new Outcome(null, reason); }
    }

    /**
     * The id of the stored (or already stored) file, or empty when it was not stored. Never throws.
     * Call it with no transaction open: all its database work runs in its own transactions (own connection; the
     * pool is small), so an open caller transaction is suspended and holds its connection meanwhile.
     */
    public Optional<UUID> store(UUID userId, byte[] bytes, String originalName) {
        return Optional.ofNullable(storeDetailed(userId, bytes, originalName).id());
    }

    /** Why a store ended as it did; the skip reasons are the {@code reason} tag of the skipped metric. */
    public enum StoreOutcome {
        STORED, DEDUPED, DISABLED, EMPTY, TOO_LARGE, UNSUPPORTED, USER_QUOTA, TOTAL_CAP, ERROR
    }

    /** {@code id} is set exactly when the outcome is STORED or DEDUPED. */
    public record StoreResult(UUID id, StoreOutcome outcome) {}

    /** Same as {@link #store} (same rules, metrics and never throwing) but says what happened. */
    public StoreResult storeDetailed(UUID userId, byte[] bytes, String originalName) {
        try {
            if (!props.isEnabled()) {
                return skipped("disabled");
            }
            if (bytes == null || bytes.length == 0) {
                return skipped("empty");
            }
            if (bytes.length > props.getMaxFileBytes().toBytes()) {
                return skipped("too_large");
            }
            FileType type;
            try {
                type = FileType.detect(bytes);
            } catch (RuntimeException e) {
                return skipped("unsupported");
            }
            String sha = sha256Hex(bytes);
            Outcome out;
            try {
                out = newTx.execute(status -> decideAndWrite(userId, bytes, originalName, type, sha));
            } catch (DataIntegrityViolationException race) {
                Optional<UUID> winner = newTx.execute(status -> repository.findIdByUserIdAndSha256(userId, sha));
                if (winner == null || winner.isEmpty()) {
                    throw race;
                }
                out = Outcome.deduped(winner.get());
            }
            if (out.id() == null) {
                return skipped(out.outcome());
            }
            if ("stored".equals(out.outcome())) {
                metrics.stored();
            } else {
                metrics.deduped();
            }
            return new StoreResult(out.id(), "stored".equals(out.outcome()) ? StoreOutcome.STORED : StoreOutcome.DEDUPED);
        } catch (Exception e) {
            log.warn("STATEMENT_FILE_STORE_FAILED {}", e.toString());
            return skipped("error");
        }
    }

    /** Runs inside the new transaction: dedupe lookup, both quota sums, then the write. */
    private Outcome decideAndWrite(UUID userId, byte[] bytes, String originalName, FileType type, String sha) {
        Optional<UUID> existing = repository.findIdByUserIdAndSha256(userId, sha);
        if (existing.isPresent()) {
            return Outcome.deduped(existing.get());
        }
        if (repository.sumSizeBytesByUserId(userId) + bytes.length > props.getMaxBytesPerUser().toBytes()) {
            return Outcome.skipped("user_quota");
        }
        long total = repository.sumSizeBytes();
        if (total + bytes.length > props.getMaxTotalBytes().toBytes()) {
            log.error("STATEMENT_FILE_STORE_FULL totalBytes={} capBytes={} incomingBytes={}",
                    total, props.getMaxTotalBytes().toBytes(), bytes.length);
            return Outcome.skipped("total_cap");
        }
        StatementFile f = new StatementFile();
        f.setUserId(userId);
        f.setSha256(sha);
        f.setSizeBytes(bytes.length);
        f.setFileType(type.name().toLowerCase(Locale.ROOT));
        f.setOriginalName(baseName(originalName));
        f.setData(encryption.encryptBytes(bytes, aad(userId, sha)));
        return Outcome.stored(repository.saveAndFlush(f).getId());
    }

    /** Binds a ciphertext to its row: a blob moved to another user's row, or one with another sha, fails to decrypt. */
    static byte[] aad(UUID userId, String sha256) {
        return (userId + ":" + sha256).getBytes(StandardCharsets.UTF_8);
    }

    /** The decrypted original bytes; empty when the file is unknown, another user's, or fails its integrity check. */
    @Transactional(readOnly = true)
    public Optional<byte[]> load(UUID userId, UUID fileId) {
        Optional<StatementFile> found = repository.findByIdAndUserId(fileId, userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StatementFile f = found.get();
        try {
            byte[] plain = encryption.decryptBytes(f.getData(), aad(userId, f.getSha256()));
            if (!sha256Hex(plain).equals(f.getSha256())) {
                log.error("STATEMENT_FILE_CORRUPT fileId={} reason=sha_mismatch", fileId);
                return Optional.empty();
            }
            return Optional.of(plain);
        } catch (RuntimeException e) {
            log.error("STATEMENT_FILE_CORRUPT fileId={} reason=decrypt_failed", fileId);
            return Optional.empty();
        }
    }

    /**
     * Deletes the user's files that no statement references and that are older than {@link #ORPHAN_GRACE} (a file is
     * stored moments before its statement row is saved); returns how many.
     */
    @Transactional
    public int deleteOrphans(UUID userId) {
        return repository.deleteOrphansByUserId(userId, orphanCutoff());
    }

    /** Deletes the file when no statement references it and it is older than the grace period; true when a row was deleted. */
    @Transactional
    public boolean deleteIfUnreferenced(UUID userId, UUID fileId) {
        return repository.deleteByIdIfUnreferenced(fileId, userId, orphanCutoff()) > 0;
    }

    /** Deletes the file at once (no grace) when no statement references it; for right after its statement was deleted. */
    @Transactional
    public boolean deleteIfUnreferencedNow(UUID userId, UUID fileId) {
        return repository.deleteByIdNowIfUnreferenced(fileId, userId) > 0;
    }

    /** Daily sweep over all users (covers files left by cascaded account deletes); returns how many were deleted. */
    @Transactional
    public int deleteAllOrphans() {
        return repository.deleteAllOrphans(orphanCutoff());
    }

    public long totalBytes() {
        return repository.sumSizeBytes();
    }

    public long bytesForUser(UUID userId) {
        return repository.sumSizeBytesByUserId(userId);
    }

    private Instant orphanCutoff() {
        return clock.instant().minus(ORPHAN_GRACE);
    }

    private StoreResult skipped(String reason) {
        metrics.skipped(reason);
        return new StoreResult(null, StoreOutcome.valueOf(reason.toUpperCase(Locale.ROOT)));
    }

    static String baseName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) {
            n = n.substring(cut + 1).trim();
        }
        if (n.isEmpty()) {
            return null;
        }
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
