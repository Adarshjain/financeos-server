package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    boolean existsByUserIdAndIsConnectedTrue(UUID userId);

    List<GmailConnection> findByIsConnectedTrue();

    /** Connections the cron should sync: wanted by the user AND holding a token Google still accepts. */
    List<GmailConnection> findByIsConnectedTrueAndAuthFailedAtIsNull();

    /** A user's mailboxes whose token died and that the user still wants connected. */
    List<GmailConnection> findByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(UUID userId);

    long countByUserIdAndIsConnectedTrueAndAuthFailedAtIsNotNull(UUID userId);

    List<GmailConnection> findByUserId(UUID userId);

    /** This user's mailboxes whose ledger holds the message (the user's own rows only), owner loaded. */
    @Query("SELECT c FROM GmailConnection c JOIN FETCH c.user WHERE c.id IN (SELECT g.connection.id FROM GmailProcessedMessage g "
            + "WHERE g.user.id = :userId AND g.gmailMessageId = :messageId)")
    List<GmailConnection> findOwningMessage(@Param("userId") UUID userId, @Param("messageId") String messageId);
}
