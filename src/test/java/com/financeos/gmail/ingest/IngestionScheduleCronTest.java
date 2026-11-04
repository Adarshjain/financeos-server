package com.financeos.gmail.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestionScheduleCronTest {

    @Test
    void defaultCronRunsAt0700ThenEveryTwoHoursFrom1000To2200Kolkata() {
        CronExpression cron = CronExpression.parse("0 0 7,10-22/2 * * *");
        ZonedDateTime t = ZonedDateTime.of(2026, 11, 4, 0, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
        List<Integer> hours = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            t = cron.next(t);
            hours.add(t.getHour());
        }
        assertEquals(List.of(7, 10, 12, 14, 16, 18, 20, 22), hours);
        assertEquals(7, cron.next(t).getHour());
        assertEquals(5, cron.next(t).getDayOfMonth());
    }
}
