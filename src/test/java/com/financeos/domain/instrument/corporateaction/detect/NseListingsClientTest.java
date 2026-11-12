package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.instrument.corporateaction.detect.NseListingsClient.Listing;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NseListingsClientTest {

    private static final String CSV = "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE\n"
            + "ITCHOTELS,ITC Hotels Limited,EQ,29-JAN-2025,1,1,INE379A01028,1\n"
            + "TMCV,Tata Motors Limited,EQ,12-NOV-2025,2,1,INE1TAE01010,2\n"
            + "QUOTED,\"Acme, Holdings \"\"Ltd\"\"\",EQ,01-MAR-2024,1,1,INE000A01010,1\n"
            + "BADDATE,Bad Date Limited,EQ,not-a-date,1,1,INE000A01011,1\n"
            + "\n";

    @Test
    void parsesHeaderWithLeadingSpacesDatesAndQuotedFields() {
        List<Listing> rows = NseListingsClient.parse(CSV);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).isEqualTo(new Listing("ITCHOTELS", "ITC Hotels Limited", LocalDate.of(2025, 1, 29), "INE379A01028"));
        assertThat(rows.get(1).listedOn()).isEqualTo(LocalDate.of(2025, 11, 12));
        assertThat(rows.get(2).name()).isEqualTo("Acme, Holdings \"Ltd\"");
    }

    @Test
    void garbageParsesToEmpty() {
        assertThat(NseListingsClient.parse("<html>blocked</html>")).isEmpty();
        assertThat(NseListingsClient.parse(null)).isEmpty();
    }

    @Test
    void bomBeforeTheHeaderIsIgnored() {
        assertThat(NseListingsClient.parse("\uFEFF" + CSV)).hasSize(3);
    }

    private static class Fake implements NseListingsClient.Fetcher {
        int calls;
        boolean fail;
        @Override
        public String fetch() throws Exception {
            calls++;
            if (fail) {
                throw new java.io.IOException("down");
            }
            return CSV;
        }
    }

    private static class Manual implements java.util.concurrent.Executor {
        final java.util.ArrayList<Runnable> queue = new java.util.ArrayList<>();
        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }
        void runAll() {
            List<Runnable> r = new java.util.ArrayList<>(queue);
            queue.clear();
            r.forEach(Runnable::run);
        }
    }

    private static class MutableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId z) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    @Test
    void coldCacheReturnsEmptyAtOnceAndRefreshesOnceInTheBackground() {
        Fake f = new Fake();
        Manual ex = new Manual();
        NseListingsClient c = new NseListingsClient(new MutableClock(), f, ex);
        assertThat(c.listings()).isEmpty();
        assertThat(c.listings()).isEmpty();
        assertThat(ex.queue).hasSize(1);
        assertThat(f.calls).isZero();
        ex.runAll();
        assertThat(f.calls).isEqualTo(1);
        assertThat(c.listings()).hasSize(3);
        assertThat(ex.queue).isEmpty();
    }

    @Test
    void listIsReusedFor24hThenStaleListIsServedWhileRefreshing() {
        Fake f = new Fake();
        Manual ex = new Manual();
        MutableClock clock = new MutableClock();
        NseListingsClient c = new NseListingsClient(clock, f, ex);
        c.listings();
        ex.runAll();
        clock.now = clock.now.plus(java.time.Duration.ofHours(23));
        assertThat(c.listings()).hasSize(3);
        assertThat(ex.queue).isEmpty();
        clock.now = clock.now.plus(java.time.Duration.ofHours(2));
        assertThat(c.listings()).hasSize(3);
        assertThat(ex.queue).hasSize(1);
        assertThat(c.listings()).hasSize(3);
        assertThat(ex.queue).hasSize(1);
        ex.runAll();
        assertThat(f.calls).isEqualTo(2);
    }

    @Test
    void failureKeepsLastGoodListAndWaitsAnHourBeforeRetrying() {
        Fake f = new Fake();
        Manual ex = new Manual();
        MutableClock clock = new MutableClock();
        NseListingsClient c = new NseListingsClient(clock, f, ex);
        c.listings();
        ex.runAll();
        clock.now = clock.now.plus(java.time.Duration.ofHours(25));
        f.fail = true;
        c.listings();
        ex.runAll();
        assertThat(c.listings()).hasSize(3);
        assertThat(ex.queue).isEmpty();
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(59));
        c.listings();
        assertThat(ex.queue).isEmpty();
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(2));
        c.listings();
        assertThat(ex.queue).hasSize(1);
    }

    @Test
    void failureWithNoListGivesEmptyWithoutThrowing() {
        Fake f = new Fake();
        f.fail = true;
        Manual ex = new Manual();
        NseListingsClient c = new NseListingsClient(new MutableClock(), f, ex);
        c.listings();
        ex.runAll();
        assertThat(c.listings()).isEmpty();
        assertThat(ex.queue).isEmpty();
    }
}
