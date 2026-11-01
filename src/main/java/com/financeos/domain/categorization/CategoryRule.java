package com.financeos.domain.categorization;

import com.financeos.domain.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "category_rules", uniqueConstraints = {
        @UniqueConstraint(name = "uk_category_rules_user_key_type", columnNames = { "user_id", "merchant_key", "match_type" })
})
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class CategoryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(name = "merchant_key", nullable = false)
    private String merchantKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private MatchType matchType = MatchType.CONTAINS;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "mcc", length = 4)
    private String mcc;

    /** The category (key into txn_categories) this rule gives matching transactions. */
    @Column(name = "category_key", nullable = false, length = 40)
    private String categoryKey;

    /** Optional sub-category of {@link #categoryKey}. */
    @Column(name = "subcategory_key", length = 80)
    private String subcategoryKey;

    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;

    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
