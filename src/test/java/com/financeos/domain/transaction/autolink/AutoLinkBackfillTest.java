package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoLinkBackfillTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AutoLinkService service = mock(AutoLinkService.class);
    private final AutoLinkBackfill backfill = new AutoLinkBackfill(users, service, Runnable::run, true);
    private final UUID userId = UUID.randomUUID();

    @Test
    void aCleanRunStampsTheMarkerAndWritesTheSummary() {
        when(users.findIdsPendingAutoLinkBackfill()).thenReturn(List.of(userId));
        AutoLinkService.RunResult clean = AutoLinkService.RunResult.ranWith(Map.of());
        when(service.runQuietly(userId)).thenReturn(clean);

        assertThat(backfill.runPending()).isEqualTo(1);

        verify(users).markAutoLinkBackfilled(org.mockito.ArgumentMatchers.eq(userId), any());
        verify(service).notifyBackfilled(userId, clean);
    }

    @Test
    void aSkippedOrFailedRunLeavesTheMarkerNullAndSendsNothing() {
        UUID other = UUID.randomUUID();
        when(users.findIdsPendingAutoLinkBackfill()).thenReturn(List.of(userId, other));
        when(service.runQuietly(userId)).thenReturn(AutoLinkService.RunResult.EMPTY);
        when(service.runQuietly(other)).thenReturn(new AutoLinkService.RunResult(Map.of(), true, true));

        assertThat(backfill.runPending()).isZero();

        verify(users, never()).markAutoLinkBackfilled(any(), any());
        verify(service, never()).notifyBackfilled(any(), any());
    }

    @Test
    void theScheduledTickOnlyHandsTheWorkToTheExecutor() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        AutoLinkBackfill b = new AutoLinkBackfill(users, service, queued::add, true);
        when(users.findIdsPendingAutoLinkBackfill()).thenReturn(List.of());

        b.tick();
        b.tick();

        assertThat(queued).hasSize(1);
        verify(users, never()).findIdsPendingAutoLinkBackfill();
        queued.get(0).run();
        verify(users).findIdsPendingAutoLinkBackfill();
        b.tick();
        assertThat(queued).hasSize(2);
    }
}
