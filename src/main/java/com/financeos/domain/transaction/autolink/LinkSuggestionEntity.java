package com.financeos.domain.transaction.autolink;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapping of {@code link_suggestions} (V115) so the test schema (ddl-auto) creates it. Rows are read and
 * written through {@link AutoLinkStore} in plain SQL; this class is the schema definition only.
 */
@Entity
@Table(name = "link_suggestions", uniqueConstraints = @UniqueConstraint(
        name = "uq_link_sugg_user_kind_txn", columnNames = { "user_id", "kind", "transaction_id" }))
@Getter
@Setter
@NoArgsConstructor
public class LinkSuggestionEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Column(name = "candidate_ids", nullable = false, length = 4000)
    private String candidateIds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
