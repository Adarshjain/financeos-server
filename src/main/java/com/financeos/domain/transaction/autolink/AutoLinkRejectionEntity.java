package com.financeos.domain.transaction.autolink;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mapping of {@code auto_link_rejections} (V115) so the test schema (ddl-auto) creates it. Rows are read
 * and written through {@link AutoLinkStore} in plain SQL; this class is the schema definition only.
 */
@Entity
@Table(name = "auto_link_rejections")
@IdClass(AutoLinkRejectionEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class AutoLinkRejectionEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Id
    @Column(nullable = false, length = 20)
    private String kind;

    @Id
    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Id
    @Column(name = "other_ref", nullable = false, length = 36)
    private String otherRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static class Key implements Serializable {
        private String userId;
        private String kind;
        private String transactionId;
        private String otherRef;

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(kind, k.kind)
                    && Objects.equals(transactionId, k.transactionId) && Objects.equals(otherRef, k.otherRef);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, kind, transactionId, otherRef);
        }
    }
}
