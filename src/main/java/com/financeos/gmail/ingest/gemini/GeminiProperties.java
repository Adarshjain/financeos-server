package com.financeos.gmail.ingest.gemini;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gemini")
@Getter
@Setter
public class GeminiProperties {
    private String apiKey;
    private String model = "gemini-3.5-flash";
    private String statementModel = "gemini-3.5-flash";
    private Integer timeout = 30000; // in milliseconds
}
