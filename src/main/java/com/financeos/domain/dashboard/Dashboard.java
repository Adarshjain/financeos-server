package com.financeos.domain.dashboard;

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

/**
 * A user-defined dashboard: a named canvas whose {@code widgets} (a JSON array of
 * {@link DashboardWidget}) reference saved reports and place them on a grid. Multi-tenant
 * via the shared {@code userFilter}.
 */
@Entity
@Table(name = "dashboards")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    /** JSON array of {@link DashboardWidget}. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(nullable = false)
    private String widgets;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Dashboard(User user, String name, String description, String widgets) {
        this.user = user;
        this.name = name;
        this.description = description;
        this.widgets = widgets;
    }
}
