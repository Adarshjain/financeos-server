package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.NameChange;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NseNameChangesClientTest {

    @Test
    void parsesTheRenameList() {
        List<NameChange> out = NseNameChangesClient.parse("""
                SYMBOL,OLD NAME OF THE COMPANY,NEW NAME OF THE COMPANY,EFFECTIVE DATE
                GALLANTT,Gallantt Metal Limited,Gallantt Ispat Limited,06-SEP-2022
                TMCV,"TML Commercial Vehicles Limited","Tata Motors Limited",12-NOV-2025
                BROKEN,,Only New Limited,01-JAN-2020
                """);

        assertThat(out).containsExactly(new NameChange("GALLANTT", "Gallantt Metal Limited", "Gallantt Ispat Limited"),
                new NameChange("TMCV", "TML Commercial Vehicles Limited", "Tata Motors Limited"));
        assertThat(NseNameChangesClient.parse("not,a,rename,list\n1,2,3,4")).isEmpty();
        assertThat(NseNameChangesClient.parse(null)).isEmpty();
    }

    @Test
    void cachesForADayAndKeepsTheLastGoodListWhenARefreshFails() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] fail = {false};
        Instant[] now = {Instant.parse("2025-01-01T00:00:00Z")};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        NseNameChangesClient client = new NseNameChangesClient(clock, () -> {
            calls.incrementAndGet();
            if (fail[0]) {
                throw new java.io.IOException("down");
            }
            return "SYMBOL,OLD NAME OF THE COMPANY,NEW NAME OF THE COMPANY\nAAA,Old Aaa Limited,Aaa Limited\n";
        }, Runnable::run);

        assertThat(client.nameChanges()).hasSize(1); // the first call starts the refresh (inline here) and sees its result
        assertThat(client.nameChanges()).hasSize(1);
        assertThat(calls.get()).isEqualTo(1);

        now[0] = now[0].plusSeconds(25 * 3600);
        fail[0] = true;
        assertThat(client.nameChanges()).hasSize(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(client.nameChanges()).hasSize(1);
        assertThat(calls.get()).isEqualTo(2); // not retried within the hour
    }
}
