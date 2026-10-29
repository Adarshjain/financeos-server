package com.financeos.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.security.UserContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InboxMemoTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private final InboxMemo memo = new InboxMemo(10, nanos::get);
    private final AtomicInteger builds = new AtomicInteger();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private InboxResponse build() {
        builds.incrementAndGet();
        return new InboxResponse(List.of(), new InboxSummaryResponse(0, 0, 0, 0), Instant.now());
    }

    @Test
    void aSecondReadWithinTheTtlReusesTheBuildAndOneAfterItRebuilds() {
        InboxResponse first = memo.get(alice, this::build);
        nanos.addAndGet(9_000_000_000L);
        assertSame(first, memo.get(alice, this::build));
        assertEquals(1, builds.get());

        nanos.addAndGet(1_000_000_000L);
        assertNotSame(first, memo.get(alice, this::build));
        assertEquals(2, builds.get());
    }

    @Test
    void eachUserHasTheirOwnEntryAndEvictionDropsOnlyTheirs() {
        InboxResponse a = memo.get(alice, this::build);
        InboxResponse b = memo.get(bob, this::build);
        assertNotSame(a, b);
        assertEquals(2, builds.get());

        memo.evict(alice);

        assertSame(b, memo.get(bob, this::build));
        assertNotSame(a, memo.get(alice, this::build));
        assertEquals(3, builds.get());
    }

    @Test
    void aBuildThatRacedAnEvictionIsNotStored() {
        memo.get(alice, () -> {
            memo.evict(alice); // a write lands while the inbox is being built
            return build();
        });
        memo.get(alice, this::build);
        assertEquals(2, builds.get());
    }

    @Test
    void aZeroTtlDisablesTheMemo() {
        InboxMemo off = new InboxMemo(0, nanos::get);
        off.get(alice, this::build);
        off.get(alice, this::build);
        assertEquals(2, builds.get());
    }

    @Test
    void theMemoIsBoundedAndKeepsWorkingPastItsLimit() {
        for (int i = 0; i < InboxMemo.MAX_ENTRIES + 50; i++) {
            memo.get(UUID.randomUUID(), this::build);
        }
        InboxResponse kept = memo.get(alice, this::build);
        assertSame(kept, memo.get(alice, this::build));
    }

    @Test
    void aWriteByTheUserEvictsButAReadDoesNot() {
        InboxMemoEvictionInterceptor interceptor = new InboxMemoEvictionInterceptor(memo);
        UserContext.setCurrentUserId(alice);
        InboxResponse a = memo.get(alice, this::build);
        InboxResponse b = memo.get(bob, this::build);

        interceptor.afterCompletion(new MockHttpServletRequest("GET", "/api/v1/accounts"), new MockHttpServletResponse(), new Object(), null);
        assertSame(a, memo.get(alice, this::build));

        interceptor.afterCompletion(new MockHttpServletRequest("POST", "/api/v1/transactions"), new MockHttpServletResponse(), new Object(), null);
        assertNotSame(a, memo.get(alice, this::build));
        assertSame(b, memo.get(bob, this::build));
    }

    @Test
    void aWriteEvictsBeforeItsHandlerSoAGetBeforeAfterCompletionRebuilds() throws Exception {
        InboxMemoEvictionInterceptor interceptor = new InboxMemoEvictionInterceptor(memo);
        UserContext.setCurrentUserId(alice);
        InboxResponse before = memo.get(alice, this::build);

        interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/transactions"), new MockHttpServletResponse(), new Object());

        assertNotSame(before, memo.get(alice, this::build));
        assertEquals(2, builds.get());
    }

    @Test
    void aPostThatOnlyReadsDoesNotEvictButAWriteBesideItDoes() throws Exception {
        InboxMemoEvictionInterceptor interceptor = new InboxMemoEvictionInterceptor(memo);
        UserContext.setCurrentUserId(alice);
        InboxResponse kept = memo.get(alice, this::build);

        for (String path : List.of("/api/v1/transactions/search", "/api/v1/transactions/count", "/api/v1/rules/preview-matches",
                "/api/v1/reports/data", "/api/v1/reports/" + UUID.randomUUID() + "/data", "/api/v1/reports/underlying",
                "/api/v1/reports/underlying/csv", "/api/v1/reports/" + UUID.randomUUID() + "/underlying",
                "/api/v1/dashboards/builtins/spend/underlying/csv")) {
            interceptor.preHandle(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object());
            interceptor.afterCompletion(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object(), null);
            assertSame(kept, memo.get(alice, this::build), path);
        }

        for (String path : List.of("/api/v1/transactions/batch-review", "/api/v1/reports", "/api/v1/reports/" + UUID.randomUUID() + "/duplicate")) {
            InboxResponse before = memo.get(alice, this::build);
            interceptor.afterCompletion(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), new Object(), null);
            assertNotSame(before, memo.get(alice, this::build), path);
        }
        // A read-only path written with another verb is still a write.
        InboxResponse before = memo.get(alice, this::build);
        interceptor.afterCompletion(new MockHttpServletRequest("PUT", "/api/v1/reports/data"), new MockHttpServletResponse(), new Object(), null);
        assertNotSame(before, memo.get(alice, this::build));
    }
}
