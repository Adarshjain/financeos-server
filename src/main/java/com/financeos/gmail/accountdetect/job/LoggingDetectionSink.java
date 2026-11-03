package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The default {@link DetectionSink} (phase 2): logs entity counts by confidence and by bank id. No numbers, names or
 * mail data. A real sink replaces this bean.
 */
@Component
public class LoggingDetectionSink implements DetectionSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingDetectionSink.class);

    @Override
    public void accept(UUID userId, Detection detection, DetectionContext ctx) {
        Map<String, Integer> byConf = new TreeMap<>();
        Map<String, Integer> byBank = new TreeMap<>();
        for (Detection.Entity e : detection.entities) {
            byConf.merge(String.valueOf(e.existsConf), 1, Integer::sum);
            byBank.merge(e.bank, 1, Integer::sum);
        }
        log.info("Account detect result: entities={}, byExists={}, byBank={}, mailsStored={}, newMails={}, statements={}, firstScan={}",
                detection.entities.size(), byConf, byBank, ctx.mailsStored(), ctx.newMails(), ctx.statements(), ctx.firstScan());
    }

}
