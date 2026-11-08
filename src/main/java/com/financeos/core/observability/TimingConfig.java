package com.financeos.core.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Installs the Spring registries into the static {@link Timing} facade at startup. */
@Component
public class TimingConfig {

    public TimingConfig(ObservationRegistry observationRegistry,
                        ObjectProvider<MeterRegistry> meterRegistry,
                        ObjectProvider<Tracer> tracer,
                        ObjectProvider<Propagator> propagator,
                        @Value("${financeos.timing.slow-log-ms:1000}") long slowLogMs) {
        Timing.install(observationRegistry, meterRegistry.getIfAvailable(), tracer.getIfAvailable(),
                propagator.getIfAvailable(), slowLogMs);
    }

    /** Stage ops are exported under this name, apart from "financeos.op": a name must be all-histogram or all-summary. */
    public static final String STAGE_METER = "financeos.stage";

    /**
     * Stage ops ({@code stage.*}) keep only count/sum/max per outcome. They are renamed {@value #STAGE_METER}
     * (Prometheus cannot expose one name as histogram for some ops and summary for others) and the SLO buckets and
     * percentiles the property config gives "financeos.op" are stripped from them. Ordered last so it runs after that config.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public static MeterFilter stageOpsWithoutBuckets() {
        return stageOpsWithoutBucketsFilter();
    }

    static MeterFilter stageOpsWithoutBucketsFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return isStageOp(id) ? id.withName(STAGE_METER) : id;
            }

            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!STAGE_METER.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(false)
                        .expiry(config.getExpiry())
                        .bufferLength(config.getBufferLength())
                        .percentilePrecision(config.getPercentilePrecision())
                        .minimumExpectedValue(config.getMinimumExpectedValueAsDouble())
                        .maximumExpectedValue(config.getMaximumExpectedValueAsDouble())
                        .build();
            }
        };
    }

    private static boolean isStageOp(Meter.Id id) {
        String op = id.getTag("op");
        return Timing.OBSERVATION_NAME.equals(id.getName()) && op != null && op.startsWith("stage.");
    }
}
