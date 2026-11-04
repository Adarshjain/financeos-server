package com.financeos.domain.notification.feed;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AppNotificationRepository extends JpaRepository<AppNotification, UUID> {

    Page<AppNotification> findByUserIdAndClearedAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndClearedAtIsNullAndReadAtIsNull(UUID userId);

    Optional<AppNotification> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndDedupeKey(UUID userId, String dedupeKey);

    Optional<AppNotification> findByUserIdAndDedupeKey(UUID userId, String dedupeKey);

    List<AppNotification> findByUserIdAndDedupeKeyIn(UUID userId, Collection<String> dedupeKeys);

    List<AppNotification> findByUserIdAndKindInAndClearedAtIsNull(UUID userId, Collection<String> kinds);

    @Modifying
    @Query("update AppNotification n set n.readAt = :now where n.userId = :userId and n.readAt is null and n.createdAt <= :before")
    int markRead(@Param("userId") UUID userId, @Param("before") Instant before, @Param("now") Instant now);

    @Modifying
    @Query("update AppNotification n set n.clearedAt = :now where n.userId = :userId and n.clearedAt is null")
    int clearAll(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Cleared in-place reminders are kept until they age out: purging one a day after it was cleared would let the
     * next daily run re-create the row of a bill that is still unpaid.
     */
    @Modifying
    @Query("delete from AppNotification n where n.createdAt < :createdCutoff or (n.clearedAt is not null and n.clearedAt < :clearedCutoff "
            + "and n.kind not in ('bill_reminder', 'emi_reminder'))")
    int purge(@Param("createdCutoff") Instant createdCutoff, @Param("clearedCutoff") Instant clearedCutoff);
}
