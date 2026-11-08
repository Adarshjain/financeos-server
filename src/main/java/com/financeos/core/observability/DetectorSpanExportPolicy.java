package com.financeos.core.observability;

import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One account-detect scan runs the detector 21 times with 10 stages each, about 230 {@code stage.detector.*} spans.
 * They are dropped at export so traces stay readable; their metrics and perf rollups are unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class DetectorSpanExportPolicy {

    static final String DETECTOR_STAGE_PREFIX = "stage.detector.";

    @Bean
    SpanExportingPredicate dropDetectorStageSpans() {
        return span -> span.getName() == null || !span.getName().startsWith(DETECTOR_STAGE_PREFIX);
    }
}
