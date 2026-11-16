package com.financeos.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The memo serves an entry only while the user's change stamp, the business date and the max age all still match. */
class InboxMemoStampTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
    private final AtomicLong stamp = new AtomicLong(7);
    private final AtomicInteger stampReads = new AtomicInteger();
    private LocalDate today = LocalDate.of(2026, 3, 10);
    private final InboxMemo memo = new InboxMemo(300, nanos::get, user -> {
        stampReads.incrementAndGet();
        return stamp.get();
    }, () -> today);
    private final AtomicInteger builds = new AtomicInteger();
    private final UUID alice = UUID.randomUUID();

    private InboxResponse build() {
        builds.incrementAndGet();
        return new InboxResponse(List.of(), new InboxSummaryResponse(0, 0, 0, 0), Instant.now());
    }

    @Test
    void anUnchangedStampSameDayAndWithinMaxAgeIsAHitThatCostsOneStampRead() {
        InboxResponse first = memo.get(alice, this::build);
        stampReads.set(0);

        assertSame(first, memo.get(alice, this::build));

        assertEquals(1, builds.get());
        assertEquals(1, stampReads.get());
    }

    @Test
    void aMovedStampMissesAndTheNewBuildIsServedAgainUntilItMovesAgain() {
        InboxResponse first = memo.get(alice, this::build);
        stamp.incrementAndGet();

        InboxResponse second = memo.get(alice, this::build);
        assertNotSame(first, second);
        assertSame(second, memo.get(alice, this::build));
        assertEquals(2, builds.get());
    }

    @Test
    void aNewBusinessDayMisses() {
        InboxResponse first = memo.get(alice, this::build);
        today = today.plusDays(1);

        assertNotSame(first, memo.get(alice, this::build));
        assertEquals(2, builds.get());
    }

    @Test
    void anEntryOlderThanTheMaxAgeMissesEvenWithTheSameStamp() {
        InboxResponse first = memo.get(alice, this::build);
        nanos.addAndGet(299_000_000_000L);
        assertSame(first, memo.get(alice, this::build));

        nanos.addAndGet(1_000_000_000L);
        assertNotSame(first, memo.get(alice, this::build));
    }

    @Test
    void aChangeLandingWhileTheBuildRunsLeavesAnEntryTheNextReadRejects() {
        memo.get(alice, () -> {
            stamp.incrementAndGet(); // another JVM commits a change mid-build
            return build();
        });

        memo.get(alice, this::build);

        assertEquals(2, builds.get());
    }

    @Test
    void anUnreadableStampNeverCachesAnything() {
        stamp.set(-1);

        memo.get(alice, this::build);
        memo.get(alice, this::build);

        assertEquals(2, builds.get());
    }

    @Test
    void otherSlotsAreCachedUnderTheSameRulesAndEvictionDropsAllOfTheUsersSlots() {
        AtomicInteger slotBuilds = new AtomicInteger();
        String first = memo.get(alice, "dataSources", () -> "v" + slotBuilds.incrementAndGet());
        assertSame(first, memo.get(alice, "dataSources", () -> "v" + slotBuilds.incrementAndGet()));
        assertEquals(1, slotBuilds.get());

        stamp.incrementAndGet();
        assertEquals("v2", memo.get(alice, "dataSources", () -> "v" + slotBuilds.incrementAndGet()));

        memo.get(alice, this::build);
        memo.evict(alice);
        memo.get(alice, this::build);
        assertEquals("v3", memo.get(alice, "dataSources", () -> "v" + slotBuilds.incrementAndGet()));
        assertEquals(2, builds.get());
    }

    @Test
    void aZeroMaxAgeDisablesTheMemoWithoutReadingTheStamp() {
        InboxMemo off = new InboxMemo(0, nanos::get, user -> {
            stampReads.incrementAndGet();
            return 1;
        }, () -> today);
        off.get(alice, this::build);
        off.get(alice, this::build);

        assertEquals(2, builds.get());
        assertEquals(0, stampReads.get());
    }
}
