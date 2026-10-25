package com.financeos.domain.investment;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A native-query price_time column reads back as an instant whatever type the driver hands over. */
class NativeTimestampTest {

    private static final Instant AT = Instant.parse("2026-10-20T09:59:58Z");

    /** Stands in for oracle.sql.TIMESTAMPTZ: the value-only conversion needs a connection, toOffsetDateTime doesn't. */
    public static final class DriverTimestamp {
        public OffsetDateTime offsetDateTimeValue() throws SQLException {
            throw new SQLException("needs a connection");
        }

        public OffsetDateTime toOffsetDateTime() {
            return AT.atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30));
        }
    }

    /** A driver type that only converts to a zoned time. */
    public static final class ZonedOnlyTimestamp {
        public ZonedDateTime toZonedDateTime() {
            return AT.atZone(java.time.ZoneId.of("Asia/Kolkata"));
        }
    }

    @Test
    void readsJavaTimeAndSqlTypes() {
        assertEquals(AT, InvestmentService.toInstant(AT));
        assertEquals(AT, InvestmentService.toInstant(AT.atOffset(java.time.ZoneOffset.UTC)));
        assertEquals(AT, InvestmentService.toInstant(Timestamp.from(AT)));
        assertNull(InvestmentService.toInstant(null));
    }

    @Test
    void readsADriverTimestampWithoutAConnection() {
        assertEquals(AT, InvestmentService.toInstant(new DriverTimestamp()));
        assertEquals(AT, InvestmentService.toInstant(new ZonedOnlyTimestamp()));
    }

    @Test
    void anUnknownValueIsNull() {
        assertNull(InvestmentService.toInstant(new Object()));
    }
}
