package com.financeos.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.type.descriptor.java.UUIDJavaType;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@FilterDef(name = "userFilter", parameters = @ParamDef(name = "userId", type = String.class))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "google_id", unique = true)
    private String googleId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "picture_url")
    private String pictureUrl;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * When the Home dashboard was seeded for this user; null = not yet. Claimed by
     * {@link UserRepository#markHomeSeeded} (conditional UPDATE), never set through the entity.
     */
    @Column(name = "home_seeded_at")
    private Instant homeSeededAt;

    /**
     * When the one-time auto-link backfill of this user's history finished; null = still to run.
     * Set only by {@link UserRepository#markAutoLinkBackfilled}.
     */
    @Column(name = "auto_link_backfilled_at", updatable = false)
    private Instant autoLinkBackfilledAt;

    /** Encrypted "yyyy-MM-dd", or "--MM-dd" when the year is unknown. Never logged. */
    @Convert(converter = com.financeos.core.crypto.EncryptedStringConverter.class)
    @Column(name = "birth_date", length = 500)
    private String birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "birth_date_source", length = 10)
    private BirthDateSource birthDateSource;

    /** When the one-time Hello screen was submitted; null = still to show. */
    @Column(name = "hello_done_at")
    private Instant helloDoneAt;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    /** Set when the user edits the name; Google sign-in no longer overwrites the name once set. */
    @Column(name = "name_edited_at")
    private Instant nameEditedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public User(String email, String googleId, String displayName, String pictureUrl) {
        this.email = email;
        this.googleId = googleId;
        this.displayName = displayName;
        this.pictureUrl = pictureUrl;
    }
}
