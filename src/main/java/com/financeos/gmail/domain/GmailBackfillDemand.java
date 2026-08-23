package com.financeos.gmail.domain;

import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gmail_backfill_demand")
@Getter
@Setter
@NoArgsConstructor
public class GmailBackfillDemand {

    @Id
    @Column(name = "user_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "floor_date", nullable = false)
    private LocalDate floorDate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public GmailBackfillDemand(User user, LocalDate floorDate) {
        this.user = user;
        this.userId = user.getId();
        this.floorDate = floorDate;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = Instant.now();
    }
}
