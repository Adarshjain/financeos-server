package com.financeos.api.shell;

import com.financeos.api.applock.dto.AppLockStatusResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import org.springframework.lang.Nullable;

/**
 * What the app shell reads on every page load and poll, in one response. Each part equals its own endpoint's value
 * ({@code GET /inbox/summary}, {@code POST /transactions/search} with the needs-review filter (its totalElements),
 * {@code GET /notifications/feed/summary} (unread), {@code GET /app-lock/status}) and is null when that part failed,
 * so one failing part does not fail the rest.
 */
public record ShellCountsResponse(
        @Nullable InboxSummaryResponse inbox,
        @Nullable Long needsReview,
        @Nullable Long notificationsUnread,
        @Nullable AppLockStatusResponse appLock) {
}
