package com.financeos.gmail.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestionScheduleEvery15MinTest {

    @Test
    void defaultCronRunsEveryQuarterHourAroundTheClock() {
        CronExpression cron = CronExpression.parse("0 */15 * * * *");
        ZonedDateTime t = ZonedDateTime.of(2026, 11, 7, 23, 50, 0, 0, ZoneId.of("Asia/Kolkata"));
        t = cron.next(t);
        assertEquals(ZonedDateTime.of(2026, 11, 8, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata")), t);
        for (int i = 0; i < 4 * 24; i++) {
            ZonedDateTime next = cron.next(t);
            assertEquals(15, java.time.Duration.between(t, next).toMinutes());
            t = next;
        }
    }
}
