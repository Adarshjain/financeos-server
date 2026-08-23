package com.financeos.gmail.ingest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gmail.ingest")
@Getter
@Setter
public class GmailIngestProperties {
    private boolean enabled = true;
    private int firstBackfillDays = 30;
    private int maxBackfillDays = 365;
    private int dateWindowDays = 3;
    private int overlapLapMinutes = 15;
    private int processBudget = 100;
    private int backfillProcessBudget = 500;
    private int retryMaxAttempts = 5;
    private int staleProcessingMinutes = 60;
}
