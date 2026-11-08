package com.financeos.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Telemetry never breaks the work it measures; and the db.queries summary has one tag-key set. */
class TimingResilienceTest {

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    private static ObservationRegistry registryWith(ObservationHandler<Observation.Context> handler) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }

    private static ObservationHandler<Observation.Context> throwing(boolean onStart, boolean onStop) {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                if (onStart) throw new IllegalStateException("start boom");
            }

            @Override
            public void onStop(Observation.Context context) {
                if (onStop) throw new IllegalStateException("stop boom");
            }
        };
    }

    @Test
    void handlerThrowingOnStart_stillRunsTheWork() {
        Timing.install(registryWith(throwing(true, false)), null, null, null, 1000L);
        AtomicInteger ran = new AtomicInteger();

        String result = Timing.call("stage.boom_start", () -> {
            ran.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void handlerThrowingOnStop_doesNotMaskResultOrException() {
        Timing.install(registryWith(throwing(false, true)), null, null, null, 1000L);

        assertThat(Timing.call("stage.boom_stop", () -> 7)).isEqualTo(7);
        assertThatThrownBy(() -> Timing.call("stage.boom_stop2", () -> {
            throw new IllegalArgumentException("real");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("real");
    }

    @Test
    void recordJobWait_neverThrows() {
        MeterRegistry broken = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Timer newTimer(io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig cfg,
                    io.micrometer.core.instrument.distribution.pause.PauseDetector pd) {
                throw new IllegalStateException("registry down");
            }
        };
        Timing.install(ObservationRegistry.NOOP, broken, null, null, 1000L);

        Timing.recordJobWait("RULE_APPLY", java.time.Duration.ofSeconds(1));
    }

    @Test
    void db_queriesSummary_registersForHttpAndJobsInPrometheus() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(prom);
        TimingObservationFilter filter = new TimingObservationFilter(provider);

        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/things");
        var http = new org.springframework.http.server.observation.ServerRequestObservationContext(req,
                new org.springframework.mock.web.MockHttpServletResponse());
        http.setPathPattern("/api/v1/things");
        filter.map(http);

        Observation.Context job = new Observation.Context();
        job.setName(Timing.OBSERVATION_NAME);
        job.setContextualName("job.rule_apply");
        job.addLowCardinalityKeyValue(io.micrometer.common.KeyValue.of("op", "job.rule_apply"));
        QueryCountObservationHandler counter = new QueryCountObservationHandler();
        if (counter.supportsContext(job)) {
            counter.onStart(job);
        }
        filter.map(job);

        String scrape = prom.scrape();
        assertThat(scrape).contains("route=\"/api/v1/things\"");
        assertThat(scrape).contains("op=\"job.rule_apply\"");
        assertThat(prom.find(TimingObservationFilter.DB_QUERIES_METER).summaries()).hasSize(2);
    }

    @Test
    void stageOps_haveNoBuckets_whileJobAndExtOpsKeepTheirs() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prom.config().meterFilter(new io.micrometer.core.instrument.config.MeterFilter() {
            @Override
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig config) {
                return io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(50_000_000d, 100_000_000d).build().merge(config);
            }
        });
        prom.config().meterFilter(TimingConfig.stageOpsWithoutBucketsFilter());
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(prom));
        Timing.install(registry, prom, null, null, 1000L);

        Timing.run("stage.demo.step", () -> { });
        Timing.run("job.demo", () -> { });
        Timing.run("ext.demo", () -> { });

        String scrape = prom.scrape();
        assertThat(scrape).contains("op=\"stage.demo.step\"");
        assertThat(lines(scrape, "stage.demo.step", "_bucket")).isEmpty();
        assertThat(lines(scrape, "stage.demo.step", "_count")).isNotEmpty();
        assertThat(lines(scrape, "stage.demo.step", "_max")).isNotEmpty();
        assertThat(lines(scrape, "job.demo", "_bucket")).isNotEmpty();
        assertThat(lines(scrape, "ext.demo", "_bucket")).isNotEmpty();
    }

    private static java.util.List<String> lines(String scrape, String op, String suffix) {
        return scrape.lines().filter(l -> l.startsWith((op.startsWith("stage.") ? "financeos_stage_seconds" : "financeos_op_seconds") + suffix) && l.contains("op=\"" + op + "\"")).toList();
    }
}
