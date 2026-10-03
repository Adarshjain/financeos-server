package com.financeos.core.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppTimeConfigurationTest {

    @AfterEach
    void reset() {
        AppTime.reset();
    }

    @Test
    void appliesTheConfiguredZoneToAppTime() {
        new AppTimeConfiguration("Asia/Tokyo").apply();
        assertEquals(ZoneId.of("Asia/Tokyo"), AppTime.zone());
    }

    @Test
    void theDefaultZoneConstantCanBeApplied() {
        AppTime.useZone(ZoneId.of("UTC"));
        new AppTimeConfiguration(AppTime.DEFAULT_ZONE).apply();
        assertEquals(ZoneId.of("Asia/Kolkata"), AppTime.zone());
    }

    @Test
    void anInvalidZoneFailsAtStartupAndLeavesTheZoneUntouched() {
        assertThrows(DateTimeException.class, () -> new AppTimeConfiguration("Not/AZone").apply());
        assertEquals(ZoneId.of("Asia/Kolkata"), AppTime.zone());
    }
}
