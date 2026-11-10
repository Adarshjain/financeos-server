package com.financeos.domain.statement.file;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** The original statement file as received, encrypted; one row per (user, sha256 of the original bytes). */
@Entity
@Table(name = "statement_files")
@Getter
@Setter
@NoArgsConstructor
public class StatementFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    /** Hex sha256 of the ORIGINAL (unencrypted) bytes. */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** Size of the original bytes. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** pdf / xls / xlsx. */
    @Column(name = "file_type", nullable = false, length = 10)
    private String fileType;

    @Column(name = "original_name", length = 255)
    private String originalName;

    /** Encrypted bytes: IV || ciphertext || tag. */
    @Lob
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "enc_version", nullable = false)
    private int encVersion = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
