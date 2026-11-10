package com.financeos.domain.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** What the parser read from one statement: its version and the encrypted, gzipped JSON snapshot. Never logged. */
@Entity
@Table(name = "statement_parse_records")
@Getter
@Setter
@NoArgsConstructor
public class StatementParseRecord {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "statement_id", length = 36)
    private UUID statementId;

    @Column(name = "parser_version", nullable = false)
    private int parserVersion;

    /** gzip(UTF-8 JSON), then EncryptionService.encryptBytes with AAD "parse:&lt;statementId&gt;". */
    @Lob
    @Column(nullable = false)
    private byte[] snapshot;

    @Column(name = "snapshot_chars", nullable = false)
    private int snapshotChars;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
