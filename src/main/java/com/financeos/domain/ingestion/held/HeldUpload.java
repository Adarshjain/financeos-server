package com.financeos.domain.ingestion.held;

import com.financeos.core.util.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A statement file uploaded without an account that could not be matched to one. The file stays in the
 * job's artifacts (retained) until it is assigned, discarded, or its job is purged.
 */
@Entity
@Table(name = "held_uploads")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class HeldUpload {

    public enum Reason { NO_MATCH, AMBIGUOUS, LOCKED }

    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_id", nullable = false, length = 36)
    private UUID jobId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "artifact_id", nullable = false, length = 36)
    private UUID artifactId;

    @Column(name = "file_index", nullable = false)
    private int fileIndex;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "detected_bank_id", length = 32)
    private String detectedBankId;

    @Column(name = "detected_last4", length = 8)
    private String detectedLast4;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16)
    private Reason reason;

    /** Comma-separated account ids that matched (AMBIGUOUS); null otherwise. */
    @Column(name = "candidate_account_ids", length = 1000)
    private String candidateAccountIds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public List<UUID> candidateIds() {
        List<UUID> out = new ArrayList<>();
        if (candidateAccountIds != null && !candidateAccountIds.isBlank()) {
            for (String part : candidateAccountIds.split(",")) {
                out.add(UUID.fromString(part.trim()));
            }
        }
        return out;
    }

    public void setCandidateIds(List<UUID> ids) {
        this.candidateAccountIds = ids == null || ids.isEmpty() ? null
                : String.join(",", ids.stream().map(UUID::toString).toList());
    }
}
