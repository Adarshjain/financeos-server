package com.financeos.gmail.reconcile;

import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * What the Gmail ingest caller needs to keep the original statement file once the reconcile transaction has
 * committed: the file's store call must run with no transaction open. {@code statementId} is set for a statement
 * created by this run; null means the statement was already ingested, and the file goes to an old statement of the
 * account with this sha that lacks one. For a created statement it also carries the parser version and parse snapshot,
 * which the caller stores (encrypted) after commit. Carries file bytes and statement text: never serialised, never logged ({@link #toString} is redacted).
 */
public record StatementFileHandoff(UUID userId, UUID accountId, @Nullable UUID statementId, String sha256,
                                   byte[] bytes, String originalName, @Nullable Integer parserVersion,
                                   @Nullable String parseSnapshot) {

    public StatementFileHandoff(UUID userId, UUID accountId, @Nullable UUID statementId, String sha256,
                                byte[] bytes, String originalName) {
        this(userId, accountId, statementId, sha256, bytes, originalName, null, null);
    }

    @Override
    public String toString() {
        return "StatementFileHandoff[redacted]";
    }
}
