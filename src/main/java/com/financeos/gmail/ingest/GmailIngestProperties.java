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
    private int dateWindowDays = 3;
}
