package com.financeos.domain.report;

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
 * A saved, user-defined report. The {@code type}-specific configuration lives in
 * {@code definition} as JSON (deserialized to the matching definition record in the
 * service layer). Multi-tenant via the shared {@code userFilter}.
 */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Report {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportType type;

    @Column(nullable = false, length = 50)
    private String datasource;

    /** The type-specific report configuration, stored as JSON text (CLOB). */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(nullable = false)
    private String definition;

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

    public Report(User user, String name, ReportType type, String datasource, String definition) {
        this.user = user;
        this.name = name;
        this.type = type;
        this.datasource = datasource;
        this.definition = definition;
    }
}
