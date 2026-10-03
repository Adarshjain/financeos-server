package com.financeos.core.time;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/** Applies {@code app.zone} (default Asia/Kolkata) to {@link AppTime} at startup. */
@Configuration
public class AppTimeConfiguration {

    private final String zone;

    public AppTimeConfiguration(@Value("${app.zone:" + AppTime.DEFAULT_ZONE + "}") String zone) {
        this.zone = zone;
    }

    @PostConstruct
    void apply() {
        AppTime.useZone(ZoneId.of(zone));
    }
}
