package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailConnectionRepository extends JpaRepository<GmailConnection, UUID> {

    Optional<GmailConnection> findByUserIdAndIsPrimaryTrue(UUID userId);

    Optional<GmailConnection> findByUserIdAndEmail(UUID userId, String email);

    List<GmailConnection> findAllByUserId(UUID userId);

    Optional<GmailConnection> findByEmail(String email);

    boolean existsByUserId(UUID userId);

    List<GmailConnection> findByIsConnectedTrue();

    /** Connections the cron should sync: wanted by the user AND holding a token Google still accepts. */
    List<GmailConnection> findByIsConnectedTrueAndAuthFailedAtIsNull();

    /** A user's mailboxes whose token died and that the user still wants connected. */
    List<GmailConnection> findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(UUID userId);

    List<GmailConnection> findByUserId(UUID userId);
}
