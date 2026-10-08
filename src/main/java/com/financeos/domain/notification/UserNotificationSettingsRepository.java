package com.financeos.domain.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationSettingsRepository extends JpaRepository<UserNotificationSettings, UUID> {

    /** Everyone the hourly tick can actually reach: push on and at least one device registered. */
    List<UserNotificationSettings> findByPushEnabledTrueAndPushSubscriptionsIsNotNull();
}
