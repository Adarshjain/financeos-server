package com.financeos.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.type.descriptor.java.UUIDJavaType;
import com.financeos.core.util.UuidGenerator;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@FilterDef(name = "userFilter", parameters = @ParamDef(name = "userId", type = UUIDJavaType.class))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UuidGenerator.generateUuid7();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
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
