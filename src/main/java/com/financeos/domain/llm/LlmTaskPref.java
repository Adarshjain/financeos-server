package com.financeos.domain.llm;

import com.financeos.domain.user.User;
import com.financeos.llm.LlmTaskGroup;
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
@Table(name = "llm_task_prefs")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class LlmTaskPref {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_group", nullable = false, length = 16)
    private LlmTaskGroup taskGroup;

    @Column(nullable = false)
    private Integer position;

    /** Id of the {@code llm.routing-options} entry the user picked. Provider and models derive from it. */
    @Column(name = "option_id", nullable = false, length = 64)
    private String optionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
