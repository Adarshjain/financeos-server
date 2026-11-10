package com.financeos.domain.statement;

import com.financeos.core.crypto.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps each statement's parse snapshot: gzip, then AES-GCM bound to the statement id, in its own table. The snapshot
 * holds the statement's narrations and full account number, so it is never logged; only ids appear in log lines.
 * Written after the statement's transaction has committed, like the original file.
 */
@Service
public class StatementParseRecords {

    private static final Logger log = LoggerFactory.getLogger(StatementParseRecords.class);

    /** A snapshot longer than this many characters is not stored. */
    static final int MAX_SNAPSHOT_CHARS = 2 * 1024 * 1024;

    private final StatementParseRecordRepository repository;
    private final EncryptionService encryption;
    private final TransactionTemplate tx;

    public StatementParseRecords(StatementParseRecordRepository repository, EncryptionService encryption,
                                 PlatformTransactionManager txManager) {
        this.repository = repository;
        this.encryption = encryption;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Stores (or replaces) the record of a statement. Never throws, and must be called with no transaction open (the
     * statement row must already be committed, and the row write takes its own short transaction). A null or over-size
     * snapshot stores nothing and removes any older record, so a stored record never describes another parse than the
     * statement's version. Any failure logs a WARN with the statement id only; the statement keeps its version, no record.
     */
    public void saveQuietly(UUID statementId, Integer parserVersion, String snapshotJson) {
        if (statementId == null || parserVersion == null) {
            return;
        }
        try {
            if (snapshotJson != null && snapshotJson.length() > MAX_SNAPSHOT_CHARS) {
                log.warn("Parse snapshot of statement {} is {} characters, over the {} limit; not stored (parser version kept)",
                        statementId, snapshotJson.length(), MAX_SNAPSHOT_CHARS);
                snapshotJson = null;
            }
            if (snapshotJson == null) {
                tx.executeWithoutResult(s -> repository.deleteById(statementId));
                return;
            }
            byte[] blob = encryption.encryptBytes(gzip(snapshotJson.getBytes(StandardCharsets.UTF_8)), aad(statementId));
            int chars = snapshotJson.length();
            try {
                tx.executeWithoutResult(s -> upsert(statementId, parserVersion, blob, chars));
            } catch (DataIntegrityViolationException race) {
                // A concurrent first insert won: the row exists now, so update it.
                tx.executeWithoutResult(s -> upsert(statementId, parserVersion, blob, chars));
            }
        } catch (Exception e) {
            log.warn("STATEMENT_PARSE_RECORD_NOT_SAVED statementId={}", statementId);
        }
    }

    private void upsert(UUID statementId, int parserVersion, byte[] blob, int chars) {
        StatementParseRecord record = repository.findById(statementId).orElseGet(StatementParseRecord::new);
        record.setStatementId(statementId);
        record.setParserVersion(parserVersion);
        record.setSnapshot(blob);
        record.setSnapshotChars(chars);
        record.setRecordedAt(Instant.now());
        repository.saveAndFlush(record);
    }

    /** The snapshot JSON; empty when there is none, or when it cannot be decrypted (logged by id only). */
    @Transactional(readOnly = true)
    public Optional<String> load(UUID statementId) {
        Optional<StatementParseRecord> found = repository.findById(statementId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] plain = gunzip(encryption.decryptBytes(found.get().getSnapshot(), aad(statementId)));
            return Optional.of(new String(plain, StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException e) {
            log.error("STATEMENT_PARSE_RECORD_CORRUPT statementId={}", statementId);
            return Optional.empty();
        }
    }

    @Transactional
    public void delete(UUID statementId) {
        repository.deleteById(statementId);
    }

    static byte[] aad(UUID statementId) {
        return ("parse:" + statementId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] plain) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(plain);
            gz.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("gzip failed", e);
        }
    }

    private static byte[] gunzip(byte[] zipped) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
            return gz.readAllBytes();
        }
    }
}
