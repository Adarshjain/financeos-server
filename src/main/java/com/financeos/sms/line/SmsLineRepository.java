package com.financeos.sms.line;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.financeos.domain.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmsLineRepository extends JpaRepository<SmsLine, UUID> {

    List<SmsLine> findByUserId(UUID userId);

    List<SmsLine> findByUserIdAndIdIn(UUID userId, java.util.Collection<UUID> ids);

    Optional<SmsLine> findByIdAndUserId(UUID id, UUID userId);

    List<SmsLine> findByUserIdAndState(UUID userId, SmsLineState state);

    @Modifying
    @Query("UPDATE SmsLine l SET l.seenByUser = true WHERE l.userId = :userId AND l.seenByUser = false")
    int markAllSeen(@Param("userId") UUID userId);

    /** Turns off lines that are ON without an account (the account was deleted). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SmsLine l SET l.state = com.financeos.sms.line.SmsLineState.OFF, l.syncFrom = NULL "
            + "WHERE l.userId = :userId AND l.state = com.financeos.sms.line.SmsLineState.ON AND l.accountId IS NULL")
    int healOrphans(@Param("userId") UUID userId);

    /** Locks the user's row until the transaction ends, so one user's summaries run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockUser(@Param("id") UUID id);

    boolean existsByUserIdAndAccountIdIsNullAndSuggestedAccountIdIsNullAndStateNot(UUID userId, SmsLineState state);
}
