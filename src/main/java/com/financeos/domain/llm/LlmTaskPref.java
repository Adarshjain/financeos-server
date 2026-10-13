package com.financeos.domain.llm;

import com.financeos.domain.user.User;
import com.financeos.llm.LlmCallOptions;
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

    /**
     * Id of the {@code llm.routing-options} entry the user picked, or {@link CustomModelSpec#OPTION_ID}
     * for a user-typed model — then {@code provider} and {@code model} carry the identity and the
     * remaining columns are the optional call knobs (see {@link LlmCallOptions}).
     */
    @Column(name = "option_id", nullable = false, length = 64)
    private String optionId;

    @Column(length = 32)
    private String provider;

    @Column(length = 255)
    private String model;

    @Column(name = "structured_output", length = 16)
    private String structuredOutput;

    @Column(name = "reasoning_effort", length = 16)
    private String reasoningEffort;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "provider_pin", length = 64)
    private String providerPin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public boolean isCustom() {
        return CustomModelSpec.isCustomOptionId(optionId);
    }

    public LlmCallOptions callOptions() {
        return LlmCallOptions.of(structuredOutput, reasoningEffort, maxOutputTokens, providerPin);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
