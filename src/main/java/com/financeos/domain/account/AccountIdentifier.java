package com.financeos.domain.account;

import com.financeos.core.exception.ValidationException;
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
@Table(name = "account_identifiers")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AccountIdentifier {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Column(name = "value", length = 32, nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private AccountIdentifierKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AccountIdentifier(UUID id, User user, Account account, String value, AccountIdentifierKind kind, Instant createdAt) {
        this.id = id;
        this.user = user;
        this.account = account;
        this.value = value;
        this.kind = kind;
        this.createdAt = createdAt;
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().replaceAll("\\s+", "");
    }

    public static void validateNormalized(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            throw new ValidationException("Identifier value cannot be blank.");
        }
        if (normalized.length() < 2 || normalized.length() > 32) {
            throw new ValidationException("Identifier value must be between 2 and 32 characters.");
        }
    }
}
